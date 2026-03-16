from nacl.hash import blake2b
from nacl.encoding import RawEncoder


def derive_key(shared_secret, id_a, id_b):

    data = shared_secret + id_a + id_b

    key = blake2b(data, digest_size=32, encoder=RawEncoder)

    return key