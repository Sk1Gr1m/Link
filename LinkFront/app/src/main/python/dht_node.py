import asyncio
import logging
import urllib.request
import json
import socket
from kademlia.network import Server

def get_public_ip():
    try:
        # Try multiple services for redundancy
        urls = ["https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"]
        for url in urls:
            try:
                with urllib.request.urlopen(url, timeout=5) as response:
                    return response.read().decode("utf-8").strip()
            except:
                continue
    except Exception as e:
        print(f"Error fetching public IP: {e}")
    return None

class DHTNode:
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(DHTNode, cls).__new__(cls)
            cls._instance.server = Server()
            cls._instance.is_connected = False
        return cls._instance

    async def _bootstrap(self):
        if not self.is_connected:
            await self.server.listen(0)
            try:
                # Common bootstrap nodes
                await self.server.bootstrap([("router.bittorrent.com", 6881), ("router.utorrent.com", 6881)])
                self.is_connected = True
            except Exception as e:
                print(f"Bootstrap failed: {e}")

    async def publish(self, fingerprint, address_json):
        await self._bootstrap()
        # Store for 2 hours (7200 seconds)
        await self.server.set(fingerprint, address_json)
        print(f"Published location for {fingerprint}: {address_json}")

    async def lookup(self, fingerprint):
        await self._bootstrap()
        result = await self.server.get(fingerprint)
        print(f"Lookup for {fingerprint}: {result}")
        return result

# Wrapper functions for Chaquopy
def publish_address(fingerprint, ip, port):
    address_json = json.dumps({"ip": ip, "port": int(port)})
    loop = asyncio.get_event_loop()
    loop.run_until_complete(DHTNode().publish(fingerprint, address_json))

def lookup_address(fingerprint):
    loop = asyncio.get_event_loop()
    result = loop.run_until_complete(DHTNode().lookup(fingerprint))
    if result:
        try:
            return json.loads(result)
        except:
            return None
    return None
