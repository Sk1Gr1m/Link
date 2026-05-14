import asyncio
import logging
import urllib.request
import urllib.error
import json
import socket
import threading
import ssl
import time
import hashlib
import os
import pickle
import random
from kademlia.network import Server

# Configure logging
logging.getLogger("kademlia").setLevel(logging.WARNING)
logging.getLogger("rpcudp").setLevel(logging.WARNING)
logging.getLogger("kademlia.protocol").setLevel(logging.WARNING)
logging.getLogger("kademlia.routing").setLevel(logging.WARNING)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("DHTNode")

# Persistent event loop for asynchronous operations
_loop = None
_loop_thread = None

def _get_or_create_loop():
    global _loop, _loop_thread
    if _loop is None or not _loop.is_running():
        _loop = asyncio.new_event_loop()
        def start_loop(l):
            asyncio.set_event_loop(l)
            l.run_forever()
        _loop_thread = threading.Thread(target=start_loop, args=(_loop,), daemon=True)
        _loop_thread.start()
        # Give it a moment to start
        time.sleep(0.1)
    return _loop

def run_async(coro, timeout=30):
    loop = _get_or_create_loop()
    if threading.current_thread() is _loop_thread:
        return None
    future = asyncio.run_coroutine_threadsafe(coro, loop)
    try:
        return future.result(timeout=timeout)
    except Exception as e:
        logger.error(f"Coroutine failed or timed out: {e}")
        return None

_ip_cache = {"public": None, "public_port": None, "local": None, "last_check": 0}

# Discover the device's public IP using STUN protocol
def get_public_ip_stun(server_index=0):
    """Discover public IP and Port using STUN (RFC 5389)"""
    stun_servers = [
        ("stun.l.google.com", 19302),
        ("stun1.l.google.com", 19302),
        ("stun2.l.google.com", 19302),
        ("stun.services.mozilla.com", 3478),
        ("stun.relay.metered.ca", 80)
    ]
    
    # Use specified server or cycle through
    servers_to_try = [stun_servers[server_index % len(stun_servers)]] if server_index >= 0 else stun_servers
    
    for host, port in servers_to_try:
        try:
            transaction_id = os.urandom(12)
            request = b'\x00\x01\x00\x00\x21\x12\xa4\x42' + transaction_id
            
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(2.5)
            addr = (socket.gethostbyname(host), port)
            sock.sendto(request, addr)
            
            data, _ = sock.recvfrom(2048)
            sock.close()
            
            if data[0:2] == b'\x01\x01':
                p = 20 
                while p < len(data):
                    attr_type = int.from_bytes(data[p:p+2], 'big')
                    attr_len = int.from_bytes(data[p+2:p+4], 'big')
                    if attr_type == 0x0020: # XOR-MAPPED-ADDRESS
                        # XORed Port
                        x_port = int.from_bytes(data[p+6:p+8], 'big')
                        res_port = x_port ^ 0x2112
                        
                        # XORed IP
                        x_ip = data[p+8:p+12]
                        magic = b'\x21\x12\xa4\x42'
                        res_ip = ".".join(str(x_ip[i] ^ magic[i]) for i in range(4))
                        return res_ip, res_port
                    p += 4 + attr_len
        except:
            continue
    return None, None

def get_public_ip():
    now = time.time()
    if _ip_cache["public"] and (now - _ip_cache["last_check"] < 600):
        return _ip_cache["public"]
    
    ip, port = get_public_ip_stun()
    if ip:
        _ip_cache["public"] = ip
        _ip_cache["public_port"] = port
        _ip_cache["last_check"] = now
        return ip

    urls = ["https://api.ipify.org", "https://ifconfig.me/ip"]
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=5) as response:
                ip = response.read().decode("utf-8").strip()
                if ip:
                    _ip_cache["public"] = ip
                    _ip_cache["last_check"] = now
                    return ip
        except: continue
    return _ip_cache["public"]

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except: return "127.0.0.1"

# Kademlia DHT node implementation
class DHTNode:
    _instance = None
    _lock = threading.Lock()
    
    def __new__(cls):
        with cls._lock:
            if cls._instance is None:
                cls._instance = super(DHTNode, cls).__new__(cls)
                cls._instance.server = None 
                cls._instance.is_connected = False
                cls._instance.extra_bootstrap_nodes = set()
                cls._instance.last_bootstrap_attempt = 0
                cls._instance.started = False
                cls._instance.my_fingerprint = None
                cls._instance.broadcast_port = 8467
                cls._instance.storage_path = None
            return cls._instance

    def initialize(self, fingerprint, storage_path=None):
        self.my_fingerprint = fingerprint
        self.storage_path = storage_path
        if not self.started:
            asyncio.run_coroutine_threadsafe(self._start_internal(), _get_or_create_loop())

    # Startup internal background tasks for DHT
    async def _start_internal(self):
        if self.started: return
        try:
            self.bootstrap_lock = asyncio.Lock()
            node_id = hashlib.sha1(self.my_fingerprint.encode()).digest()
            self.server = Server(node_id=node_id)
            for port in [8468, 6881, 0]:
                try:
                    await self.server.listen(port)
                    self.started = True
                    break
                except: continue
            
            if self.started:
                await self._load_state()
                asyncio.create_task(self._bootstrap_loop())
                asyncio.create_task(self._broadcast_loop())
                asyncio.create_task(self._listen_broadcast_loop())
                asyncio.create_task(self._keep_alive_loop())
        except: pass

    async def _keep_alive_loop(self):
        while True:
            try:
                if self.server:
                    if self.is_connected:
                        random_key = hashlib.sha1(str(random.random()).encode()).hexdigest()
                        await self.server.get(random_key)
                    else:
                        await asyncio.to_thread(get_public_ip_stun)
            except: pass
            await asyncio.sleep(30)

    async def _load_state(self):
        if not self.storage_path or not os.path.exists(self.storage_path): return
        try:
            with open(self.storage_path, "rb") as f:
                neighbors = pickle.load(f)
            if neighbors: await self.server.bootstrap(neighbors)
        except: pass

    async def _bootstrap_loop(self):
        while True:
            try:
                neighbors = self.server.bootstrappable_neighbors() if self.server else []
                self.is_connected = len(neighbors) > 0
                
                # More aggressive bootstrap when offline
                await self._bootstrap(force=not self.is_connected)
                
                if not self.is_connected:
                    # If still not connected, try clearing cache and re-listening
                    await asyncio.sleep(10)
                else:
                    await asyncio.sleep(300)
            except: await asyncio.sleep(30)

    # Connect to the DHT network via bootstrap nodes
    async def _bootstrap(self, force=False):
        async with self.bootstrap_lock:
            now = time.time()
            if not force and self.is_connected and (now - self.last_bootstrap_attempt < 300): return
            self.last_bootstrap_attempt = now
            try:
                # Use a wider variety of bootstrap nodes. 
                # Note: BitTorrent nodes use a different protocol but some may be multi-protocol.
                # However, having a larger list increases the chance of finding a compatible node.
                bootstrap_nodes = [
                    ("router.bittorrent.com", 6881),
                    ("router.utorrent.com", 6881),
                    ("dht.transmissionbt.com", 6881),
                    ("dht.libtorrent.org", 25401),
                    ("bootstrap.jami.net", 4222), 
                    ("dht.opendht.org", 4222), 
                    ("node.opendht.org", 4222),
                    ("bootstrap.libreswan.org", 4222)
                ]
                resolved = []
                for host, port in bootstrap_nodes:
                    try:
                        addr_info = await asyncio.to_thread(socket.getaddrinfo, host, port, socket.AF_INET, socket.SOCK_DGRAM)
                        for item in addr_info: resolved.append((item[4][0], port))
                    except: continue
                
                # Hardcoded stable IPs (highly reliable nodes from various networks)
                resolved.extend([
                    ("67.215.246.10", 6881),   # BitTorrent
                    ("212.129.33.59", 6881),   # Transmission
                    ("198.199.122.25", 4222),  # Jami
                    ("151.80.35.48", 4222),    # OpenDHT
                    ("82.165.138.163", 4222)   # OpenDHT
                ])
                
                # Prioritize extra nodes (manually added via QR)
                resolved = list(self.extra_bootstrap_nodes) + resolved
                if not resolved: return
                
                logger.info(f"Bootstrapping DHT with {len(resolved)} nodes. Force={force}")
                await self.server.bootstrap(resolved)
                await self._find_local_neighbors()
            except Exception as e:
                logger.error(f"Bootstrap error: {e}")

    async def _find_local_neighbors(self):
        local_ip = get_local_ip()
        if not local_ip or local_ip == "127.0.0.1": return
        parts = local_ip.split('.')
        base = f"{parts[0]}.{parts[1]}.{parts[2]}."
        speculative = []
        for i in [1, 100, 101, 107, 254]:
            ip = base + str(i)
            if ip != local_ip: speculative.append((ip, 8468))
        try: await self.server.bootstrap(speculative)
        except: pass

    # Periodically broadcast presence on the local network
    async def _broadcast_loop(self):
        while True:
            if self.started and self.server and self.server.protocol:
                try:
                    port = self.server.protocol.transport.get_extra_info('sockname')[1]
                    data = json.dumps({"type": "LINK_DISCOVERY", "fingerprint": self.my_fingerprint, "port": port}).encode()
                    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    sock.sendto(data, ("255.255.255.255", self.broadcast_port))
                    sock.close()
                except: pass
            await asyncio.sleep(20)

    async def _listen_broadcast_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(('', self.broadcast_port))
            sock.setblocking(False)
            while True:
                try:
                    data, addr = await asyncio.to_thread(sock.recvfrom, 1024)
                    msg = json.loads(data.decode())
                    if msg.get("type") == "LINK_DISCOVERY" and msg.get("fingerprint") != self.my_fingerprint:
                        await self.add_bootstrap_node(addr[0], msg.get("port"))
                except: await asyncio.sleep(1)
        except: sock.close()

    async def add_bootstrap_node(self, ip, port):
        if (ip, int(port)) not in self.extra_bootstrap_nodes:
            logger.info(f"Adding manual bootstrap node: {ip}:{port}")
            self.extra_bootstrap_nodes.add((ip, int(port)))
            if self.started: 
                await self._bootstrap(force=True)
                # After bootstrapping from a manual node, immediately try to refresh 
                # to find our place in the network relative to them.
                if self.server:
                    asyncio.create_task(self.server.bootstrap([(ip, int(port))]))

    # Publish information to the DHT
    async def publish(self, key, value):
        if not self.started: return False
        try:
            await self.server.set(key, value)
            return True
        except: return False

    async def lookup(self, key):
        if not self.started: return None
        try: return await self.server.get(key)
        except: return None

    def get_status_dict(self):
        neighbors = self.server.bootstrappable_neighbors() if self.server else []
        listen_port = self.server.protocol.transport.get_extra_info('sockname')[1] if (self.server and self.server.protocol and self.server.protocol.transport) else None
        return {
            "is_connected": len(neighbors) > 0,
            "neighbor_count": len(neighbors),
            "protocol_listening": self.server is not None,
            "listen_port": listen_port,
            "local_ip": get_local_ip(),
            "public_ip": _ip_cache["public"] or "Checking...",
            "public_port": _ip_cache.get("public_port")
        }

    # Store a signaling message (Offer, Answer, or Candidate) in the DHT
    async def put_signal(self, target, sender, sig_type, content):
        if sig_type == "CANDIDATE":
            key = f"sig:{target}:{sender}:cands"
            current = await self.lookup(key)
            try:
                cands = json.loads(current) if current else []
                if not isinstance(cands, list): cands = []
            except: cands = []
            if content not in cands:
                cands.append(content)
                if len(cands) > 20: cands = cands[-20:]
                return await self.publish(key, json.dumps(cands))
            return True
        
        # Use a consistent key for OFFERs so the receiver can find them without knowing the sender yet
        if sig_type == "OFFER":
            key = f"sig:{target}::OFFER"
        else:
            key = f"sig:{target}:{sender}:{sig_type}"
            
        data = {"type": sig_type, "from": sender, "content": content, "timestamp": time.time()}
        return await self.publish(key, json.dumps(data))

    async def get_signal(self, me, peer, sig_type):
        if sig_type == "CANDIDATE":
            key = f"sig:{me}:{peer}:cands"
            return await self.lookup(key)
        key = f"sig:{me}:{peer}:{sig_type}"
        res = await self.lookup(key)
        return json.loads(res) if res else None

def initialize_dht(fingerprint, storage_path=None):
    DHTNode().initialize(fingerprint, storage_path)

def clear_ip_cache():
    _ip_cache["public"] = None
    _ip_cache["local"] = None
    _ip_cache["last_check"] = 0

def set_public_ip(ip, port=None):
    _ip_cache["public"] = ip
    if port: _ip_cache["public_port"] = port
    _ip_cache["last_check"] = time.time()

def get_dht_status():
    return json.dumps(DHTNode().get_status_dict())

def force_rebootstrap():
    run_async(DHTNode()._bootstrap(force=True))

def add_dht_bootstrap(ip, port):
    run_async(DHTNode().add_bootstrap_node(ip, port))

def get_stun_info():
    ip1, port1 = get_public_ip_stun(0)
    if not ip1: return json.dumps({"error": "STUN failed"})
    local_ip = get_local_ip()
    ip2, port2 = get_public_ip_stun(1)
    nat_type = "None" if ip1 == local_ip else ("Full-Cone" if port1 == port2 else "Symmetric")
    return json.dumps({"public_ip": ip1, "public_port": port1, "local_ip": local_ip, "nat_type": nat_type, "is_symmetric": port1 != port2})

def put_value(key, value):
    return run_async(DHTNode().publish(key, value))

def get_value(key):
    return run_async(DHTNode().lookup(key))

def publish_address(fingerprint, public_ip, local_ip, port, dht_port=0):
    data = {"public_ip": public_ip, "local_ip": local_ip, "port": int(port), "dht_port": int(dht_port), "time": int(time.time())}
    return put_value(fingerprint, json.dumps(data))
        
def lookup_address(fingerprint):
    res = get_value(fingerprint)
    return json.loads(res) if res else None

def send_signal(target, sender, signal_type, content):
    return run_async(DHTNode().put_signal(target, sender, signal_type, content))

def receive_signal(me, peer, signal_type):
    res = run_async(DHTNode().get_signal(me, peer, signal_type))
    return res if signal_type == "CANDIDATE" else (json.dumps(res) if res else None)
