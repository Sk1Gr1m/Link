from nacl.secret import SecretBox
from nacl.public import PrivateKey, PublicKey, SealedBox
from nacl.signing import SigningKey, VerifyKey
from nacl.utils import random
from nacl.hash import blake2b, generichash
from nacl.encoding import RawEncoder
from nacl.bindings import crypto_scalarmult
from .session import Session
import hashlib

def ensure_bytes(b):
    if b is None:
        return None
    if isinstance(b, (bytes, bytearray)):
        return b
    # Handle Chaquopy's jarray or list of signed integers from Java
    return bytes(x & 0xff for x in b)

def _to_curve_pub(ed_pub_bytes):
    ed_pub_bytes = ensure_bytes(ed_pub_bytes)
    vk = VerifyKey(ed_pub_bytes)
    return vk.to_curve25519_public_key()

def _to_curve_priv(ed_priv_bytes):
    ed_priv_bytes = ensure_bytes(ed_priv_bytes)
    sk = SigningKey(ed_priv_bytes)
    return sk.to_curve25519_private_key()

def encrypt_for_peer(message_str, peer_ed_public_key_bytes):
    """Converts Ed25519 to Curve25519 and encrypts via SealedBox."""
    peer_ed_public_key_bytes = ensure_bytes(peer_ed_public_key_bytes)
    curve_pub = _to_curve_pub(peer_ed_public_key_bytes)
    sealed_box = SealedBox(curve_pub)
    return bytes(sealed_box.encrypt(message_str.encode()))

def encrypt(message_str, peer_ed_public_key_bytes):
    """Alias for encrypt_for_peer to match Kotlin usage."""
    return encrypt_for_peer(message_str, peer_ed_public_key_bytes)

def decrypt_with_my_key(ciphertext_bytes, my_ed_private_key_bytes):
    """Decrypts using Ed25519 private key by converting to Curve25519."""
    ciphertext_bytes = ensure_bytes(ciphertext_bytes)
    my_ed_private_key_bytes = ensure_bytes(my_ed_private_key_bytes)
    
    # SigniningClient.kt calls this with identityManager.getEncryptionPrivateKeyBytes()
    # which actually returns the Ed25519 seed/private key in this codebase.
    curve_priv = _to_curve_priv(my_ed_private_key_bytes)
    return SealedBox(curve_priv).decrypt(ciphertext_bytes).decode()

def generate_ephemeral_keypair():
    sk = PrivateKey.generate()
    return [bytes(sk), bytes(sk.public_key)]

def establish_session(my_ed_priv, my_eph_priv, peer_ed_pub, peer_eph_pub):
    """Triple Diffie-Hellman-ish session establishment."""
    try:
        # Force strict bytes conversion immediately to satisfy PyNaCl
        def strict_32_bytes(b):
            if b is None: return b'\x00' * 32
            # Handle Chaquopy jarray/list conversion
            if not isinstance(b, (bytes, bytearray)):
                try:
                    res = bytes(x & 0xff for x in b)
                except:
                    res = b'\x00' * 32
            else:
                res = bytes(b)
            
            if len(res) == 32: return res
            if len(res) > 32: return res[:32]
            return res.ljust(32, b'\x00')

        my_ed_priv = strict_32_bytes(my_ed_priv)
        my_eph_priv = strict_32_bytes(my_eph_priv)
        peer_ed_pub = strict_32_bytes(peer_ed_pub)
        peer_eph_pub = strict_32_bytes(peer_eph_pub)

        # Convert all to Curve25519
        me_static = _to_curve_priv(my_ed_priv)
        me_eph = PrivateKey(my_eph_priv)
        
        peer_static = _to_curve_pub(peer_ed_pub)
        peer_eph = PublicKey(peer_eph_pub)
        
        # Simple shared secret derivation using low-level bindings
        ss1 = crypto_scalarmult(bytes(me_static), bytes(peer_eph))
        ss2 = crypto_scalarmult(bytes(me_eph), bytes(peer_static))
        ss3 = crypto_scalarmult(bytes(me_eph), bytes(peer_eph))
        
        # Ensure deterministic order of ss1 and ss2 for both peers
        ss_ordered = sorted([ss1, ss2])
        
        combined = ss_ordered[0] + ss_ordered[1] + ss3
        shared_secret = hashlib.blake2b(combined, digest_size=32).digest()
        return Session(shared_secret)
    except Exception as e:
        import traceback
        # Use simple print as logging might not be initialized here
        print(f"establish_session error: {str(e)}")
        # Return None so Kotlin can handle it gracefully instead of crashing the process
        return None
