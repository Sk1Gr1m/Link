import json
import base64
import time
from nacl.signing import SigningKey, VerifyKey

class Identity:
    def __init__(self, seed=None, created_at=None):
        if seed:
            # Seed must be 32 bytes
            if isinstance(seed, (bytes, bytearray)):
                self.signing_key = SigningKey(seed[:32])
            elif isinstance(seed, str):
                self.signing_key = SigningKey(seed.encode()[:32])
            else:
                # Handle list from Java
                seed_bytes = bytes(x & 0xff for x in seed)
                self.signing_key = SigningKey(seed_bytes[:32])
        else:
            self.signing_key = SigningKey.generate()
        
        self.verify_key = self.signing_key.verify_key
        self.created_at = int(created_at) if created_at is not None else int(time.time())

    def get_seed_bytes(self):
        return bytes(self.signing_key)

    def get_public_key_bytes(self):
        return bytes(self.verify_key)

    def get_created_at(self):
        return self.created_at

    def get_fingerprint(self):
        return get_fingerprint(bytes(self.verify_key))

    def sign(self, message_bytes):
        """Signs bytes and returns the signature."""
        return self.signing_key.sign(message_bytes).signature

    def get_qr_data(self, username):
        data = {
            "u": username,
            "k": base64.b64encode(bytes(self.verify_key)).decode('utf-8'),
            "t": self.created_at
        }
        return json.dumps(data)

    def get_connection_qr(self, username, sdp, sdp_type):
        """Creates a QR string containing identity AND WebRTC signaling data."""
        data = {
            "u": username,
            "k": base64.b64encode(bytes(self.verify_key)).decode('utf-8'),
            "t": self.created_at,
            "s": sdp,
            "st": sdp_type # "offer" or "answer"
        }
        return json.dumps(data)

def get_fingerprint(pub_key_bytes):
    import hashlib
    if not isinstance(pub_key_bytes, (bytes, bytearray)):
        pub_key_bytes = bytes(x & 0xff for x in pub_key_bytes)
    h = hashlib.sha256(pub_key_bytes).hexdigest().upper()
    return ":".join(h[i:i+4] for i in range(0, 16, 4)) # First 16 chars for readability

def verify_signature(pub_key_bytes, signature, message_bytes):
    """Verifies a signature against a public key and message."""
    try:
        verify_key = VerifyKey(pub_key_bytes)
        verify_key.verify(message_bytes, signature)
        return True
    except Exception:
        return False

def parse_qr_data(qr_string):
    data = json.loads(qr_string)
    # Return as a tuple: (username, key_bytes, timestamp, sdp, sdp_type)
    return (
        data["u"], 
        base64.b64decode(data["k"]), 
        data.get("t", 0),
        data.get("s"),    # Optional SDP
        data.get("st")    # Optional SDP type
    )
