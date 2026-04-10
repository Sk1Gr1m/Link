import struct


class Packet:

    HELLO = 1
    RESPONSE = 2
    MESSAGE = 3

    @staticmethod
    def pack(ptype, payload):

        length = len(payload)

        header = struct.pack("!BI", ptype, length)

        return header + payload

    @staticmethod
    def unpack(data):

        ptype, length = struct.unpack("!BI", data[:5])

        payload = data[5:5+length]

        return ptype, payload