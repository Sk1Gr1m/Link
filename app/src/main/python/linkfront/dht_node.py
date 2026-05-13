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
logging.getLogger("kademlia").setLevel(logging.INFO)
logging.getLogger("rpcudp").setLevel(logging.INFO)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("DHTNode")

# Persistent event loop in a background thread
_loop = asyncio.new_event_loop()

def _start_background_loop(loop):
    asyncio.set_event_loop(loop)
    try:
        loop.run_forever()
    except Exception as e:
        logger.error(f"Event loop died: {e}")

_loop_thread = threading.Thread(target=_start_background_loop, args=(_loop,), daemon=True)
_loop_thread.start()

def run_async(coro, timeout=30):
    if threading.current_thread() is _loop_thread:
        return None
    future = asyncio.run_coroutine_threadsafe(coro, _loop)
    try:
        return future.result(timeout=timeout)
    except Exception as e:
        logger.error(f"Coroutine failed or timed out: {e}")
        return None

_ip_cache = {"public": None, "local": None, "last_check": 0}

def get_public_ip():
    now = time.time()
    if _ip_cache["public"] and (now - _ip_cache["last_check"] < 600):
        return _ip_cache["public"]
    
    urls = [
        "https://api.ipify.org", 
        "https://ifconfig.me/ip", 
        "https://icanhazip.com",
        "http://checkip.amazonaws.com",
        "http://ifconfig.io/ip"
    ]
    ctx = ssl._create_unverified_context()
    for url in urls:
        try:
            logger.info(f"Checking public IP via {url}")
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=10, context=ctx) as response:
                ip = response.read().decode("utf-8").strip()
                if ip and len(ip) >= 7 and len(ip) <= 15 and ip.count('.') == 3:
                    logger.info(f"Discovered public IP: {ip}")
                    _ip_cache["public"] = ip
                    _ip_cache["last_check"] = now
                    return ip
        except Exception as e:
            logger.debug(f"Failed to fetch IP from {url}: {e}")
            continue
    
    logger.warning("Could not discover public IP via HTTP")
    return _ip_cache["public"]

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and ip != "Checking...":
            _ip_cache["local"] = ip
            return ip
    except:
        pass
    return "127.0.0.1"

class DHTNode:
    _instance = None
    _lock = threading.Lock()
    
    def __new__(cls):
        with cls._lock:
            if cls._instance is None:
                cls._instance = super(DHTNode, cls).__new__(cls)
                cls._instance.server = None 
                cls._instance.is_connected = False
                cls._instance.bootstrap_lock = asyncio.Lock()
                cls._instance.extra_bootstrap_nodes = set()
                cls._instance.last_bootstrap_attempt = 0
                cls._instance.started = False
                cls._instance.my_fingerprint = None
                cls._instance.broadcast_port = 8467
                cls._instance.storage_path = None
            return cls._instance

    def initialize(self, fingerprint, storage_path=None):
        if not fingerprint:
            logger.error("Cannot initialize DHT with empty fingerprint")
            return
        self.my_fingerprint = fingerprint
        self.storage_path = storage_path
        if not self.started:
            asyncio.run_coroutine_threadsafe(self._start_internal(), _loop)

    async def _start_internal(self):
        if self.started: return
        try:
            logger.info(f"Initializing DHT for fingerprint: {self.my_fingerprint}")
            # Use stable node ID derived from fingerprint
            node_id = hashlib.sha1(self.my_fingerprint.encode()).digest()
            self.server = Server(node_id=node_id)
            
            # Try a range of ports, prioritizing standard DHT and "safe" ports
            # Safe ports like 53 (DNS) or 123 (NTP) sometimes bypass carrier UDP blocks
            for port in [8468, 6881, 18468, 3478, 5060, 8080, 53, 123, 0]:
                try:
                    await self.server.listen(port)
                    logger.info(f"DHT listening on port {port}")
                    self.started = True
                    break
                except:
                    continue
            
            if self.started:
                # Load previous routing table if available
                await self._load_state()
                # Start background tasks
                asyncio.create_task(self._bootstrap_loop())
                asyncio.create_task(self._broadcast_loop())
                asyncio.create_task(self._listen_broadcast_loop())
                asyncio.create_task(self._save_state_loop())
        except Exception as e:
            logger.error(f"DHT start error: {e}")

    async def _save_state_loop(self):
        while True:
            await asyncio.sleep(600)  # Save every 10 minutes
            await self._save_state()

    async def _save_state(self):
        if not self.storage_path or not self.server:
            return
        try:
            neighbors = self.server.bootstrappable_neighbors()
            if neighbors:
                with open(self.storage_path, "wb") as f:
                    pickle.dump(neighbors, f)
                logger.info(f"Saved {len(neighbors)} neighbors to {self.storage_path}")
        except Exception as e:
            logger.error(f"Failed to save DHT state: {e}")

    async def _load_state(self):
        if not self.storage_path or not os.path.exists(self.storage_path):
            return
        try:
            with open(self.storage_path, "rb") as f:
                neighbors = pickle.load(f)
            if neighbors:
                logger.info(f"Loading {len(neighbors)} neighbors from cache")
                await self.server.bootstrap(neighbors)
        except Exception as e:
            logger.error(f"Failed to load DHT state: {e}")

    async def _bootstrap_loop(self):
        while True:
            # Check actual neighbor count
            neighbors = []
            if self.server and self.server.protocol:
                neighbors = self.server.bootstrappable_neighbors()
            
            self.is_connected = len(neighbors) > 0
            
            await self._bootstrap()
            # If not connected, retry much faster (10s instead of 30s)
            delay = 10 if not self.is_connected else 300
            await asyncio.sleep(delay)

    async def _bootstrap(self, force=False):
        async with self.bootstrap_lock:
            now = time.time()
            if not force and self.is_connected and (now - self.last_bootstrap_attempt < 300):
                return
            if not force and not self.is_connected and (now - self.last_bootstrap_attempt < 30):
                return
            
            self.last_bootstrap_attempt = now
            try:
                bootstrap_nodes = [
                    ("router.bittorrent.com", 6881),
                    ("router.utorrent.com", 6881),
                    ("dht.transmissionbt.com", 6881),
                    ("router.silotis.us", 6881),
                    ("router.hyanat.com", 6881),
                    ("bootstrap.jami.net", 4222),
                    ("bootstrap.jami.net", 4500),
                    ("bootstrap.jami.net", 80),
                    ("bootstrap.jami.net", 443),
                    ("bootstrap.ring.cx", 4222),
                    ("dht.opendht.org", 4222),
                    ("dht.opendht.org", 80),
                    ("dht.opendht.org", 443),
                    ("dht.libtorrent.org", 25401)
                ]
                
                resolved_nodes = []
                for host, port in bootstrap_nodes:
                    try:
                        # Use getaddrinfo to get all available IPs
                        addr_info = await _loop.run_in_executor(None, lambda: socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_DGRAM))
                        for item in addr_info:
                            resolved_nodes.append((item[4][0], port))
                    except:
                        continue

                # Stable IPs (Bulletproof backup)
                stable_ips = [
                    # Jami stable nodes
                    ("198.199.122.25", 4222), ("162.213.125.10", 4222),
                    # OpenDHT stable nodes
                    ("45.33.56.241", 4222), ("192.168.1.1", 4222),
                    # BitTorrent stable nodes
                    ("67.215.246.10", 6881), ("82.221.139.222", 6881),
                    ("212.129.33.59", 6881), ("87.98.162.88", 6881),
                    # Additional stable nodes on various ports
                    ("198.199.122.25", 443), ("162.213.125.10", 80),
                    ("45.33.56.241", 443), ("45.76.69.176", 53),
                    # OpenDHT official nodes
                    ("bootstrap.jami.net", 4222), ("bootstrap.jami.net", 80),
                    ("45.33.56.241", 443), ("162.213.125.10", 443)
                ]
                for node in stable_ips:
                    if node not in resolved_nodes: resolved_nodes.append(node)

                for node in self.extra_bootstrap_nodes:
                    if node not in resolved_nodes: resolved_nodes.append(node)

                if not resolved_nodes: return

                logger.info(f"Bootstrapping DHT with {len(resolved_nodes)} nodes. Current neighbors: {len(self.server.bootstrappable_neighbors() if self.server else [])}")
                await self.server.bootstrap(resolved_nodes)
                
                await self._find_local_neighbors()
                
                await asyncio.sleep(5)
                neighbors = self.server.bootstrappable_neighbors()
                self.is_connected = len(neighbors) > 0
                if self.is_connected:
                    logger.info(f"DHT Connected successfully with {len(neighbors)} neighbors")
                else:
                    logger.warning("DHT Bootstrap failed to find any neighbors. Retrying...")
            except Exception as e:
                logger.error(f"DHT Bootstrap error: {e}")

    async def _find_local_neighbors(self):
        local_ip = get_local_ip()
        if not local_ip or local_ip == "127.0.0.1": return
        parts = local_ip.split('.')
        base = f"{parts[0]}.{parts[1]}.{parts[2]}."
        speculative = [
            ("192.168.0.1", 8468), ("192.168.1.1", 8468),
            ("192.168.0.254", 8468), ("192.168.1.254", 8468)
        ]
        for i in [1, 100, 101, 107, 254]:
            ip = base + str(i)
            if ip != local_ip:
                speculative.append((ip, 8468))
        try:
            await self.server.bootstrap(speculative)
        except:
            pass

    async def _broadcast_loop(self):
        while True:
            if self.started and self.server and self.server.protocol:
                try:
                    port = self.server.protocol.transport.get_extra_info('sockname')[1]
                    data = json.dumps({
                        "type": "LINK_DISCOVERY",
                        "fingerprint": self.my_fingerprint,
                        "port": port
                    }).encode()
                    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    broadcast_addrs = ["255.255.255.255"]
                    local_ip = get_local_ip()
                    if local_ip and local_ip != "127.0.0.1":
                        parts = local_ip.split('.')
                        broadcast_addrs.append(f"{parts[0]}.{parts[1]}.{parts[2]}.255")
                    for addr in set(broadcast_addrs):
                        try:
                            sock.sendto(data, (addr, self.broadcast_port))
                        except: pass
                    sock.close()
                except Exception as e:
                    logger.warning(f"Broadcast loop error: {e}")
            await asyncio.sleep(20)

    async def _listen_broadcast_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(('', self.broadcast_port))
            sock.setblocking(False)
            logger.info(f"Listening for local broadcasts on port {self.broadcast_port}")
            while True:
                try:
                    data, addr = await _loop.run_in_executor(None, lambda: sock.recvfrom(1024))
                    msg = json.loads(data.decode())
                    if msg.get("type") == "LINK_DISCOVERY" and msg.get("fingerprint") != self.my_fingerprint:
                        await self.add_bootstrap_node(addr[0], msg.get("port"))
                except:
                    await asyncio.sleep(1)
        except:
            sock.close()

    async def add_bootstrap_node(self, ip, port):
        if not ip or not port: return
        node_tuple = (ip, int(port))
        if node_tuple not in self.extra_bootstrap_nodes:
            self.extra_bootstrap_nodes.add(node_tuple)
            if self.started:
                await self._bootstrap(force=True)

    async def publish(self, key, value):
        if not self.started: return False
        try:
            await self.server.set(key, value)
            return True
        except: return False

    async def lookup(self, key):
        if not self.started: return None
        try:
            return await self.server.get(key)
        except: return None

    def get_status_dict(self):
        neighbors = []
        routing_table_size = 0
        if self.server and self.server.protocol:
            try:
                neighbors = self.server.bootstrappable_neighbors()
                routing_table_size = len(self.server.protocol.router.get_neighbors())
            except: pass
        listen_port = None
        if self.server and self.server.protocol and self.server.protocol.transport:
            try:
                listen_port = self.server.protocol.transport.get_extra_info('sockname')[1]
            except: pass
        return {
            "is_connected": len(neighbors) > 0 or routing_table_size > 0,
            "neighbor_count": max(len(neighbors), routing_table_size),
            "protocol_listening": self.server is not None,
            "listen_port": listen_port,
            "local_ip": get_local_ip(),
            "public_ip": _ip_cache["public"] or "Checking..."
        }

def initialize_dht(fingerprint, storage_path=None):
    DHTNode().initialize(fingerprint, storage_path)

def set_public_ip(ip):
    if not ip or ip == "Checking..." or ip == "None": return
    _ip_cache["public"] = ip
    _ip_cache["last_check"] = time.time()

def get_dht_status():
    return json.dumps(DHTNode().get_status_dict())

def add_dht_bootstrap(ip, port):
    run_async(DHTNode().add_bootstrap_node(ip, port))

def clear_ip_cache():
    _ip_cache["public"] = None
    _ip_cache["local"] = None
    _ip_cache["last_check"] = 0

def put_value(key, value):
    return run_async(DHTNode().publish(key, value)) or False

def get_value(key):
    return run_async(DHTNode().lookup(key), timeout=15)

def publish_address(fingerprint, public_ip, local_ip, port, dht_port=0):
    data = {
        "public_ip": public_ip if public_ip and public_ip != "None" else "None",
        "local_ip": local_ip,
        "port": int(port),
        "dht_port": int(dht_port),
        "time": int(time.time())
    }
    return put_value(fingerprint, json.dumps(data))
        
def lookup_address(fingerprint):
    result = get_value(fingerprint)
    if result:
        try: return json.loads(result)
        except: pass
    return None
