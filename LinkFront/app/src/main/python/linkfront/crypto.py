from nacl.secret import SecretBox
from nacl.utils import random
from nacl.hash import blake2b
from nacl.encoding import RawEncoder

# Temporary hardcoded key for isolating the transport/encryption layer
_FIXED_KEY = b"0" * 32
_box = SecretBox(_FIXED_KEY)

def encrypt(msg: str):
    """Encrypts a string message using a hardcoded key."""
    return _box.encrypt(msg.encode())

def decrypt(cipher):
    """Decrypts a byte array ciphertext using a hardcoded key."""
    # Java's byte[] contains signed bytes (-128 to 127). 
    # When Chaquopy passes this to Python, it may be treated as a sequence of signed integers.
    # PyNaCl's C bindings expect unsigned bytes (0 to 255).
    if not isinstance(cipher, (bytes, bytearray)):
        # Convert signed bytes to unsigned bytes
        cipher = bytes(x & 0xff for x in cipher)
    return _box.decrypt(cipher).decode()

def derive_key(shared_secret, id_a, id_b):
    """Original key derivation function (kept for future handshake implementation)."""
    data = shared_secret + id_a + id_b
    key = blake2b(data, digest_size=32, encoder=RawEncoder)
    return key
