from nacl.secret import SecretBox
from nacl.utils import random


class Session:

    def __init__(self, key):

        self.box = SecretBox(key)
        self.counter = 0
        self.last_seen = -1

    def encrypt(self, message):

        nonce = random(24)

        payload = f"{self.counter}:{message}".encode()

        encrypted = self.box.encrypt(payload, nonce)

        self.counter += 1

        return encrypted

    def decrypt(self, data):

        decrypted = self.box.decrypt(data).decode()

        counter, msg = decrypted.split(":", 1)

        counter = int(counter)

        if counter <= self.last_seen:
            raise Exception("Replay detected")

        self.last_seen = counter

        return msg