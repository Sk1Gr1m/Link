from nacl.public import PrivateKey, PublicKey
from nacl.bindings import crypto_scalarmult

def generate_keypair():
    """Generates an ephemeral X25519 keypair."""
    priv = PrivateKey.generate()
    pub = priv.public_key
    return bytes(priv), bytes(pub)

def derive_shared(priv_bytes, peer_pub_bytes):
    """Derives a shared session key using X25519."""
    # Ensure bytes
    if not isinstance(priv_bytes, (bytes, bytearray)):
        priv_bytes = bytes(x & 0xff for x in priv_bytes)
    if not isinstance(peer_pub_bytes, (bytes, bytearray)):
        peer_pub_bytes = bytes(x & 0xff for x in peer_pub_bytes)
        
    return crypto_scalarmult(priv_bytes, peer_pub_bytes)
