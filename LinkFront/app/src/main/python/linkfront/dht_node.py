import asyncio
import logging
import urllib.request
import urllib.error
import json
import socket
import threading
from kademlia.network import Server

# Configure logging
logging.getLogger("kademlia").setLevel(logging.WARNING)
logging.getLogger("rpcudp").setLevel(logging.WARNING)

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

def run_async(coro):
    """Utility to run a coroutine in the background event loop and wait for result."""
    future = asyncio.run_coroutine_threadsafe(coro, _loop)
    try:
        return future.result(timeout=30)
    except asyncio.TimeoutError:
        logger.error("Coroutine timed out")
        return None
    except Exception as e:
        logger.error(f"Coroutine failed: {e}")
        return None

def get_public_ip():
    urls = ["https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"]
    for url in urls:
        try:
            logger.info(f"Fetching public IP from {url}")
            with urllib.request.urlopen(url, timeout=5) as response:
                ip = response.read().decode("utf-8").strip()
                logger.info(f"Public IP: {ip}")
                return ip
        except Exception as e:
            logger.warning(f"Failed to fetch IP from {url}: {e}")
            continue
    return None

class DHTNode:
    _instance = None
    _lock = threading.Lock()
    
    def __new__(cls):
        with cls._lock:
            if cls._instance is None:
                cls._instance = super(DHTNode, cls).__new__(cls)
                cls._instance.server = Server()
                cls._instance.is_connected = False
                cls._instance.bootstrap_lock = asyncio.Lock()
            return cls._instance

    async def _bootstrap(self):
        async with self.bootstrap_lock:
            # Check if protocol exists before calling bootstrappable_neighbors
            if self.server.protocol:
                neighbors = self.server.bootstrappable_neighbors()
                if neighbors:
                    self.is_connected = True
                    return

            try:
                # Start listening if not already doing so
                if not self.server.protocol:
                    # Listen on a fixed port if possible to help with NAT, 
                    # but 0 is safer for avoiding "address in use"
                    await self.server.listen(8468) 
                
                logger.info("Bootstrapping DHT node...")
                bootstrap_nodes = [
                    ("router.bittorrent.com", 6881),
                    ("router.utorrent.com", 6881),
                    ("dht.transmissionbt.com", 6881),
                    ("dht.libtorrent.org", 25401),
                    ("router.silotis.us", 6881),
                    ("dht.aelitis.com", 6881),
                    ("router.bitcomet.com", 6881),
                ]
                
                # Resolve hostnames first
                resolved_nodes = []
                for host, port in bootstrap_nodes:
                    try:
                        ip = await _loop.run_in_executor(None, lambda: socket.gethostbyname(host))
                        resolved_nodes.append((ip, port))
                    except Exception as e:
                        # Silencing resolution errors as they are expected for some public bootstrap nodes
                        pass

                # Stable IP-based DHT nodes (Mainline DHT)
                stable_ips = [
                    ("67.215.246.10", 6881),   # OpenDNS/Cisco
                    ("82.221.139.222", 6881),  # transmissionbt.com
                    ("212.129.33.59", 6881),   # router.bittorrent.com
                    ("87.98.162.88", 6881),    # Debian / Various
                    ("52.58.153.216", 6881),   # libtorrent node
                    ("151.80.120.114", 6881),
                    ("174.129.43.20", 6881),
                    ("192.121.121.14", 6881),  
                    ("2.16.204.13", 6881),
                    ("212.129.33.50", 6881),
                    ("82.221.103.244", 6881),
                    ("185.157.221.247", 25401),
                    ("1.1.1.1", 6881), # Cloudflare (placeholder, won't work but for testing)
                ]

                # Use a specific known-working node if others fail
                # For Link project, we should ideally have our own bootstrap nodes
                # Adding some more common ones from known DHT lists
                extra_nodes = [
                    ("62.210.198.161", 6881),
                    ("212.129.33.52", 6881),
                    ("185.157.221.245", 25401),
                ]
                stable_ips.extend(extra_nodes)
                
                # Check if we can reach any stable IP via a quick ping-like check
                for ip, port in stable_ips:
                    resolved_nodes.append((ip, port))

                if not resolved_nodes:
                    logger.error("No bootstrap nodes available")
                    return

                # Perform bootstrap with resolved nodes
                # Kademlia's bootstrap is async, let's give it some time
                await self.server.bootstrap(resolved_nodes)
                
                # Wait a bit for neighbors to respond
                for _ in range(5):
                    await asyncio.sleep(1)
                    neighbors = self.server.bootstrappable_neighbors()
                    if neighbors:
                        self.is_connected = True
                        logger.info(f"DHT Node connected with {len(neighbors)} neighbors")
                        return
                
                self.is_connected = False
                logger.warning("DHT Bootstrap found no neighbors after waiting.")
                    
            except Exception as e:
                logger.error(f"DHT Bootstrap exception: {e}")
                self.is_connected = False
                # If 8468 fails, try random port
                try:
                    await self.server.listen(0)
                except:
                    pass

    async def publish(self, fingerprint, address_json):
        await self._bootstrap()
        if self.is_connected:
            await self.server.set(fingerprint, address_json)
            logger.info(f"Published location for {fingerprint}")
        else:
            logger.error("Skipping publish: No DHT neighbors found")

    async def lookup(self, fingerprint):
        await self._bootstrap()
        if self.is_connected:
            result = await self.server.get(fingerprint)
            return result
        return None

    def get_status(self):
        try:
            # Get the current routing table neighbors
            neighbors = []
            if self.server.protocol:
                neighbors = self.server.bootstrappable_neighbors()
            
            # If we have 0 neighbors but the server IS listening, 
            # and we've been running for a while, it's a UDP block.
            status = {
                "is_connected": len(neighbors) > 0,
                "neighbor_count": len(neighbors),
                "protocol_listening": self.server.protocol is not None,
                "udp_status": "OK" if len(neighbors) > 0 else "BLOCKED_OR_SEARCHING"
            }
            return status
        except Exception as e:
            return {
                "is_connected": False,
                "neighbor_count": 0,
                "protocol_listening": False,
                "error": str(e)
            }

# Wrapper functions for Chaquopy
def get_dht_status():
    status = DHTNode().get_status()
    return json.dumps(status)

def put_value(key, value):
    """Puts a generic string value into the DHT."""
    try:
        run_async(DHTNode().publish(key, value))
        return True
    except Exception as e:
        logger.error(f"Failed to put to DHT: {e}")
        return False

def get_value(key):
    """Gets a generic string value from the DHT."""
    try:
        result = run_async(DHTNode().lookup(key))
        return result
    except Exception as e:
        logger.error(f"Failed to get from DHT: {e}")
    return None

def publish_address(fingerprint, ip, port):
    address_json = json.dumps({"ip": ip, "port": int(port)})
    put_value(fingerprint, address_json)
        
def lookup_address(fingerprint):
    result = get_value(fingerprint)
    if result:
        try:
            return json.loads(result)
        except:
            pass
    return None
