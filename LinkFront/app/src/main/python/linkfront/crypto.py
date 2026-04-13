from nacl.secret import SecretBox
from nacl.public import PrivateKey, PublicKey, SealedBox
from nacl.utils import random
from nacl.hash import blake2b
from nacl.encoding import RawEncoder

# Temporary hardcoded key for isolating the transport/encryption layer
_FIXED_KEY = b"0" * 32
_box = SecretBox(_FIXED_KEY)

def encrypt_for_peer(message_str, peer_public_key_bytes):
    """Encrypts a message so only the owner of the private key can read it."""
    if not isinstance(peer_public_key_bytes, (bytes, bytearray)):
        peer_public_key_bytes = bytes(x & 0xff for x in peer_public_key_bytes)
    
    # Ed25519 keys (for signing) need to be converted to Curve25519 (for encryption)
    # but for simplicity in this DHT phase, we use SealedBox which handles the public key.
    # Note: linkfront.identity uses SigningKeys. We need the encryption equivalent.
    public_key = PublicKey(peer_public_key_bytes)
    sealed_box = SealedBox(public_key)
    return sealed_box.encrypt(message_str.encode())

def decrypt_with_my_key(ciphertext_bytes, my_private_key_bytes):
    """Decrypts a message encrypted with SealedBox using my private key."""
    if not isinstance(ciphertext_bytes, (bytes, bytearray)):
        ciphertext_bytes = bytes(x & 0xff for x in ciphertext_bytes)
    if not isinstance(my_private_key_bytes, (bytes, bytearray)):
        my_private_key_bytes = bytes(x & 0xff for x in my_private_key_bytes)
        
    private_key = PrivateKey(my_private_key_bytes)
    sealed_box = SealedBox(private_key)
    return sealed_box.decrypt(ciphertext_bytes).decode()

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
