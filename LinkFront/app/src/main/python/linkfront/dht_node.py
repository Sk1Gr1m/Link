import asyncio
import logging
import urllib.request
import urllib.error
import json
import socket
import threading
from kademlia.network import Server

# Configure logging
# Suppress noisy library logs unless they are critical
logging.getLogger("kademlia").setLevel(logging.WARNING)
logging.getLogger("rpcudp").setLevel(logging.ERROR)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("DHTNode")

# Persistent event loop in a background thread
_loop = asyncio.new_event_loop()

def _start_background_loop(loop):
    asyncio.set_event_loop(loop)
    loop.run_forever()

_loop_thread = threading.Thread(target=_start_background_loop, args=(_loop,), daemon=True)
_loop_thread.start()

def run_async(coro):
    """Utility to run a coroutine in the background event loop and wait for result."""
    future = asyncio.run_coroutine_threadsafe(coro, _loop)
    return future.result()

def get_public_ip():
    urls = ["https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"]
    for url in urls:
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                return response.read().decode("utf-8").strip()
        except (urllib.error.URLError, socket.timeout):
            continue
        except Exception as e:
            logger.error(f"Unexpected error fetching public IP from {url}: {e}")
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
            return cls._instance

    async def _bootstrap(self):
        # If we have neighbors, we consider ourselves connected
        if self.is_connected and self.server.bootstrappable_neighbors():
            return

        try:
            # Start listening if not already doing so
            if not self.server.protocol:
                await self.server.listen(0)
            
            logger.info("Bootstrapping DHT node...")
            # Use a more diverse set of bootstrap nodes
            # Added more stable nodes and potential mobile-friendly ones
            bootstrap_nodes = [
                ("router.bittorrent.com", 6881),
                ("router.utorrent.com", 6881),
                ("dht.transmissionbt.com", 6881),
                ("dht.libtorrent.org", 25401),
                ("router.bitcomet.com", 6881),
                ("dht.aelitis.com", 6881)
            ]
            
            await self.server.bootstrap(bootstrap_nodes)
            
            # Check if we actually found anyone
            neighbors = self.server.bootstrappable_neighbors()
            if neighbors:
                self.is_connected = True
                logger.info(f"DHT Node connected with {len(neighbors)} neighbors")
            else:
                # If standard bootstrap fails, try a more aggressive approach or wait
                self.is_connected = False
                logger.warning("DHT Bootstrap found no neighbors. Retrying on next request.")
                
        except Exception as e:
            logger.error(f"DHT Bootstrap exception: {e}")
            self.is_connected = False

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

# Wrapper functions for Chaquopy
def publish_address(fingerprint, ip, port):
    address_json = json.dumps({"ip": ip, "port": int(port)})
    try:
        run_async(DHTNode().publish(fingerprint, address_json))
    except Exception as e:
        logger.error(f"Failed to publish to DHT: {e}")
        
def lookup_address(fingerprint):
    try:
        result = run_async(DHTNode().lookup(fingerprint))
        if result:
            return json.loads(result)
    except Exception as e:
        logger.error(f"Failed to lookup from DHT: {e}")
    return None
