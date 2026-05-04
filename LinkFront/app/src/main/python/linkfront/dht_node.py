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
    if _ip_cache["public"] and (now - _ip_cache["last_check"] < 300):
        return _ip_cache["public"]
    
    urls = ["https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"]
    ctx = ssl._create_unverified_context()
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3, context=ctx) as response:
                ip = response.read().decode("utf-8").strip()
                if ip and ip != "Checking...":
                    _ip_cache["public"] = ip
                    _ip_cache["last_check"] = now
                    return ip
        except:
            continue
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
            
            # Try a range of ports
            for port in [8468, 6881, 3478, 5060, 0]:
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
                self.is_connected = True
        except Exception as e:
            logger.error(f"Failed to load DHT state: {e}")

    async def _bootstrap_loop(self):
        while True:
            await self._bootstrap()
            delay = 30 if not self.is_connected else 300
            await asyncio.sleep(delay)

    async def _bootstrap(self, force=False):
        async with self.bootstrap_lock:
            now = time.time()
            if not force and self.is_connected and (now - self.last_bootstrap_attempt < 300):
                return
            
            self.last_bootstrap_attempt = now
            try:
                bootstrap_nodes = [
                    ("router.bittorrent.com", 6881),
                    ("router.utorrent.com", 6881),
                    ("dht.transmissionbt.com", 6881),
                    ("dht.libtorrent.org", 25401),
                    ("router.silotis.us", 6881),
                    ("dht.aelitis.com", 6881),
                    ("router.bitcomet.com", 6881),
                    ("dht.anidex.info", 6881),
                ]
                
                resolved_nodes = []
                for host, port in bootstrap_nodes:
                    try:
                        ip = await _loop.run_in_executor(None, lambda: socket.gethostbyname(host))
                        resolved_nodes.append((ip, port))
                    except:
                        pass

                stable_ips = [
                    ("67.215.246.10", 6881), ("82.221.139.222", 6881),
                    ("212.129.33.59", 6881), ("87.98.162.88", 6881),
                    ("151.80.120.114", 6881), ("212.129.33.50", 6881)
                ]
                for node in stable_ips:
                    if node not in resolved_nodes: resolved_nodes.append(node)

                for node in self.extra_bootstrap_nodes:
                    if node not in resolved_nodes: resolved_nodes.append(node)

                if not resolved_nodes: return

                logger.info(f"Bootstrapping DHT with {len(resolved_nodes)} nodes")
                await self.server.bootstrap(resolved_nodes)
                
                # Check for success
                for _ in range(3):
                    await asyncio.sleep(2)
                    neighbors = self.server.bootstrappable_neighbors()
                    if neighbors:
                        self.is_connected = True
                        logger.info(f"DHT Connected with {len(neighbors)} neighbors")
                        return
                
                self.is_connected = False
            except Exception as e:
                logger.error(f"DHT Bootstrap error: {e}")

    async def _broadcast_loop(self):
        """Periodically broadcast presence on the local network."""
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
                        if len(parts) == 4:
                            broadcast_addrs.append(f"{parts[0]}.{parts[1]}.{parts[2]}.255")

                    for addr in set(broadcast_addrs):
                        try:
                            sock.sendto(data, (addr, self.broadcast_port))
                        except Exception as e:
                            logger.debug(f"Failed to send broadcast to {addr}: {e}")
                    sock.close()
                except Exception as e:
                    logger.warning(f"Broadcast loop error: {e}")

            # Broadcast more frequently if not connected
            delay = 10 if not self.is_connected else 30
            await asyncio.sleep(delay)

    async def _listen_broadcast_loop(self):
        """Listen for presence broadcasts from other peers on the local network."""
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
                        peer_ip = addr[0]
                        peer_port = msg.get("port")
                        logger.info(f"Discovered local peer via broadcast: {peer_ip}:{peer_port}")
                        await self.add_bootstrap_node(peer_ip, peer_port)
                except (BlockingIOError, json.JSONDecodeError):
                    await asyncio.sleep(1)
                except Exception as e:
                    logger.warning(f"Broadcast listen error: {e}")
                    await asyncio.sleep(5)
        except Exception as e:
            logger.error(f"Could not bind broadcast listener: {e}")
            sock.close()

    async def add_bootstrap_node(self, ip, port):
        if not ip or not port: return
        if ip == "Checking..." or ip == "0.0.0.0" or ip == "None": return
        node_tuple = (ip, int(port))
        if node_tuple not in self.extra_bootstrap_nodes:
            logger.info(f"Adding manual bootstrap node: {ip}:{port}")
            self.extra_bootstrap_nodes.add(node_tuple)
            if self.started:
                await self._bootstrap(force=True)

    async def publish(self, key, value):
        if not self.started: return False
        try:
            await self.server.set(key, value)
            return True
        except:
            return False

    async def lookup(self, key):
        if not self.started: return None
        try:
            return await self.server.get(key)
        except:
            return None

    def get_status_dict(self):
        try:
            neighbors = []
            routing_table_size = 0
            if self.server and self.server.protocol:
                try:
                    neighbors = self.server.bootstrappable_neighbors()
                except:
                    pass
                    
                if self.server.protocol.router:
                    try:
                        routing_table_size = len(self.server.protocol.router.get_neighbors())
                    except:
                        pass
            
            listen_port = None
            protocol_listening = False
            if self.server and self.server.protocol:
                protocol_listening = True
                if hasattr(self.server.protocol, 'transport') and self.server.protocol.transport:
                    try:
                        sockname = self.server.protocol.transport.get_extra_info('sockname')
                        if sockname:
                            listen_port = sockname[1]
                    except:
                        pass

            connected = len(neighbors) > 0 or routing_table_size > 0

            return {
                "is_connected": connected,
                "neighbor_count": max(len(neighbors), routing_table_size),
                "protocol_listening": protocol_listening,
                "listen_port": listen_port,
                "local_ip": get_local_ip(),
                "public_ip": _ip_cache["public"] or "Checking..."
            }
        except Exception as e:
            logger.error(f"Error in get_status_dict: {e}", exc_info=True)
            return {
                "is_connected": False,
                "neighbor_count": 0,
                "protocol_listening": False,
                "listen_port": None,
                "local_ip": "127.0.0.1",
                "public_ip": "Error",
                "error": str(e)
            }

def initialize_dht(fingerprint, storage_path=None):
    DHTNode().initialize(fingerprint, storage_path)

def get_dht_status():
    return json.dumps(DHTNode().get_status_dict())

def add_dht_bootstrap(ip, port):
    run_async(DHTNode().add_bootstrap_node(ip, port))

def put_value(key, value):
    return run_async(DHTNode().publish(key, value)) or False

def get_value(key):
    return run_async(DHTNode().lookup(key))

def publish_address(fingerprint, public_ip, local_ip, port, dht_port=0):
    node = DHTNode()
    if not node.started or not node.is_connected:
        logger.warning("Delaying address publish: DHT not ready or not connected")
        # Optional: We could start a background task to retry this later
        # For now, SignalingClient.kt handles retries via heartbeat
        return False

    if public_ip == "Checking..." or public_ip == "None":
        public_ip = None
    if local_ip == "Checking..." or local_ip == "0.0.0.0":
        return False

    data = {
        "public_ip": public_ip if public_ip else "None",
        "local_ip": local_ip,
        "port": int(port),
        "dht_port": int(dht_port),
        "time": int(time.time())
    }
    return put_value(fingerprint, json.dumps(data))
        
def lookup_address(fingerprint):
    result = get_value(fingerprint)
    if result:
        try:
            return json.loads(result)
        except:
            pass
    return None
