from nacl.signing import SigningKey


class Identity:

    def __init__(self):
        self.signing_key = SigningKey.generate()
        self.verify_key = self.signing_key.verify_key

    def public_key(self):
        return self.verify_key.encode().hex()

    def sign(self, data: bytes):
        signed = self.signing_key.sign(data)
        return signed.signature.hex()