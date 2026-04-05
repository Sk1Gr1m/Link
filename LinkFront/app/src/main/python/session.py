from nacl.secret import SecretBox
from nacl.utils import random

class Session:
    def __init__(self, key):
        # Java's byte[] can come in as signed integers. 
        # Convert to unsigned bytes for PyNaCl.
        if not isinstance(key, (bytes, bytearray)):
            key = bytes(x & 0xff for x in key)
        self.box = SecretBox(key)
        self.counter = 0
        self.last_seen = -1

    def encrypt(self, message):
        nonce = random(24)
        # Include counter for replay protection
        payload = f"{self.counter}:{message}".encode()
        encrypted = self.box.encrypt(payload, nonce)
        self.counter += 1
        return bytes(encrypted)

    def decrypt(self, data):
        # Java's byte[] can come in as signed integers.
        if not isinstance(data, (bytes, bytearray)):
            data = bytes(x & 0xff for x in data)
        
        decrypted = self.box.decrypt(data).decode()
        counter_str, msg = decrypted.split(":", 1)
        counter = int(counter_str)

        if counter <= self.last_seen:
            raise Exception(f"Replay detected: counter {counter} <= last_seen {self.last_seen}")

        self.last_seen = counter
        return msg
