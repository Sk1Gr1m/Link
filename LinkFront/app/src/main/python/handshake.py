from nacl.public import PrivateKey
from nacl.public import PublicKey
from nacl.public import Box

from .crypto import derive_key


class Handshake:

    def __init__(self):

        self.private = PrivateKey.generate()
        self.public = self.private.public_key

    def public_bytes(self):
        return self.public.encode()

    def create_shared(self, peer_public, id_a, id_b):

        peer_key = PublicKey(peer_public)

        box = Box(self.private, peer_key)

        shared = box.shared_key()

        return derive_key(shared, id_a, id_b)