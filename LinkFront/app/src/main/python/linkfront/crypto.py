from nacl.secret import SecretBox
from nacl.public import PrivateKey, PublicKey, SealedBox
from nacl.signing import SigningKey, VerifyKey
from nacl.utils import random
from nacl.hash import blake2b
from nacl.encoding import RawEncoder
from nacl.bindings import crypto_scalarmult

class Session:
    def __init__(self, shared_key):
        if not isinstance(shared_key, (bytes, bytearray)):
            shared_key = bytes(x & 0xff for x in shared_key)
        self.shared_key = shared_key
        self.box = SecretBox(shared_key)
    
    def encrypt(self, message_str):
        if isinstance(message_str, str):
            message_str = message_str.encode()
        return bytes(self.box.encrypt(message_str))
        
    def decrypt(self, ciphertext):
        if not isinstance(ciphertext, (bytes, bytearray)):
            ciphertext = bytes(x & 0xff for x in ciphertext)
        return self.box.decrypt(ciphertext).decode()

def _to_curve_pub(ed_pub_bytes):
    if not isinstance(ed_pub_bytes, (bytes, bytearray)):
        ed_pub_bytes = bytes(x & 0xff for x in ed_pub_bytes)
    vk = VerifyKey(ed_pub_bytes)
    return vk.to_curve25519_public_key()

def _to_curve_priv(ed_priv_bytes):
    if not isinstance(ed_priv_bytes, (bytes, bytearray)):
        ed_priv_bytes = bytes(x & 0xff for x in ed_priv_bytes)
    sk = SigningKey(ed_priv_bytes)
    return sk.to_curve25519_private_key()

def encrypt_for_peer(message_str, peer_ed_public_key_bytes):
    """Converts Ed25519 to Curve25519 and encrypts via SealedBox."""
    if not isinstance(peer_ed_public_key_bytes, (bytes, bytearray)):
        peer_ed_public_key_bytes = bytes(x & 0xff for x in peer_ed_public_key_bytes)
    curve_pub = _to_curve_pub(peer_ed_public_key_bytes)
    sealed_box = SealedBox(curve_pub)
    return bytes(sealed_box.encrypt(message_str.encode()))

def encrypt(message_str, peer_ed_public_key_bytes):
    """Alias for encrypt_for_peer to match Kotlin usage."""
    return encrypt_for_peer(message_str, peer_ed_public_key_bytes)

def decrypt_with_my_key(ciphertext_bytes, my_curve_private_key_bytes):
    """Decrypts using Curve25519 private key."""
    if not isinstance(ciphertext_bytes, (bytes, bytearray)):
        ciphertext_bytes = bytes(x & 0xff for x in ciphertext_bytes)
    pk = PrivateKey(bytes(x & 0xff for x in my_curve_private_key_bytes))
    return SealedBox(pk).decrypt(ciphertext_bytes).decode()

def generate_ephemeral_keypair():
    sk = PrivateKey.generate()
    return bytes(sk), bytes(sk.public_key)

def establish_session(my_ed_priv, my_eph_priv, peer_ed_pub, peer_eph_pub):
    """Triple Diffie-Hellman-ish session establishment."""
    # Convert all inputs to bytes and handle Java-style signed bytes if needed
    def ensure_bytes(b):
        if not isinstance(b, (bytes, bytearray)):
            return bytes(x & 0xff for x in b)
        return b

    my_ed_priv = ensure_bytes(my_ed_priv)
    my_eph_priv = ensure_bytes(my_eph_priv)
    peer_ed_pub = ensure_bytes(peer_ed_pub)
    peer_eph_pub = ensure_bytes(peer_eph_pub)

    # Convert all to Curve25519
    me_static = _to_curve_priv(my_ed_priv)
    me_eph = PrivateKey(my_eph_priv)
    
    peer_static = _to_curve_pub(peer_ed_pub)
    peer_eph = PublicKey(peer_eph_pub)
    
    # Simple shared secret derivation using low-level bindings
    ss1 = crypto_scalarmult(bytes(me_static), bytes(peer_eph))
    ss2 = crypto_scalarmult(bytes(me_eph), bytes(peer_static))
    ss3 = crypto_scalarmult(bytes(me_eph), bytes(peer_eph))
    
    shared_secret = blake2b(ss1 + ss2 + ss3, encoder=RawEncoder)
    return Session(bytes(shared_secret))
