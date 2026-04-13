from nacl.secret import SecretBox
from nacl.public import PrivateKey, PublicKey, SealedBox
from nacl.signing import SigningKey, VerifyKey
from nacl.utils import random
from nacl.hash import blake2b
from nacl.encoding import RawEncoder

class Session:
    def __init__(self, shared_key):
        self.box = SecretBox(shared_key)
    
    def encrypt(self, message_str):
        if isinstance(message_str, str):
            message_str = message_str.encode()
        return self.box.encrypt(message_str)
        
    def decrypt(self, ciphertext):
        if not isinstance(ciphertext, (bytes, bytearray)):
            ciphertext = bytes(x & 0xff for x in ciphertext)
        return self.box.decrypt(ciphertext).decode()

def _to_curve_pub(ed_pub_bytes):
    vk = VerifyKey(bytes(x & 0xff for x in ed_pub_bytes))
    return vk.to_curve25519_public_key()

def _to_curve_priv(ed_priv_bytes):
    sk = SigningKey(bytes(x & 0xff for x in ed_priv_bytes))
    return sk.to_curve25519_private_key()

def encrypt_for_peer(message_str, peer_ed_public_key_bytes):
    """Converts Ed25519 to Curve25519 and encrypts via SealedBox."""
    curve_pub = _to_curve_pub(peer_ed_public_key_bytes)
    sealed_box = SealedBox(curve_pub)
    return sealed_box.encrypt(message_str.encode())

def decrypt_with_my_key(ciphertext_bytes, my_curve_private_key_bytes):
    """Decrypts using Curve25519 private key."""
    if not isinstance(ciphertext_bytes, (bytes, bytearray)):
        ciphertext_bytes = bytes(x & 0xff for x in ciphertext_bytes)
    pk = PrivateKey(bytes(x & 0xff for x in my_curve_private_key_bytes))
    return SealedBox(pk).decrypt(ciphertext_bytes).decode()

def generate_ephemeral_keypair():
    return PrivateKey.generate()

def establish_session(my_ed_priv, my_eph_priv, peer_ed_pub, peer_eph_pub):
    """Triple Diffie-Hellman-ish session establishment."""
    # Convert all to Curve25519
    me_static = PrivateKey(_to_curve_priv(my_ed_priv))
    me_eph = PrivateKey(bytes(x & 0xff for x in my_eph_priv))
    
    peer_static = _to_curve_pub(peer_ed_pub)
    peer_eph = PublicKey(bytes(x & 0xff for x in peer_eph_pub))
    
    # Simple shared secret derivation
    ss1 = me_static.shared_key(peer_eph)
    ss2 = me_eph.shared_key(peer_static)
    ss3 = me_eph.shared_key(peer_eph)
    
    shared_secret = blake2b(ss1 + ss2 + ss3, digest_size=32).digest()
    return Session(shared_secret)
