"""Small RFC 6455 client for local contract probes; no third-party dependencies."""
import base64
import hashlib
import json
import os
import socket
import struct
import time


class Closed(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(f"WebSocket closed with code {code}")


class WebSocket:
    def __init__(self, port, credential, expected_status=101):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=6)
        try:
            key = base64.b64encode(os.urandom(16)).decode()
            device, token = credential
            request = (f"GET /v1/ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
                       "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                       f"X-Coach-Device: {device}\r\nAuthorization: Bearer {token}\r\n\r\n")
            self.socket.sendall(request.encode())
            header = bytearray()
            while not header.endswith(b"\r\n\r\n"):
                header.extend(self.read(1))
                if len(header) > 16384:
                    raise ValueError("oversized WS handshake")
            status = int(header.split(b" ", 2)[1])
            if status != expected_status:
                raise ValueError(f"WS handshake expected {expected_status}, received {status}")
            if status != 101:
                self.close()
                return
            accept = base64.b64encode(hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
            headers = dict(line.split(b":", 1) for line in bytes(header).split(b"\r\n")[1:] if b":" in line)
            if not any(k.lower() == b"sec-websocket-accept" and v.strip() == accept for k, v in headers.items()):
                raise ValueError("invalid WS accept")
        except BaseException:
            self.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        self.socket.close()

    def read(self, size):
        value = bytearray()
        while len(value) < size:
            part = self.socket.recv(size - len(value))
            if not part:
                raise EOFError("WS transport closed")
            value.extend(part)
        return bytes(value)

    def frame(self, opcode, payload):
        size = len(payload)
        mask = os.urandom(4)
        prefix = bytes([0x80 | opcode])
        if size < 126:
            prefix += bytes([0x80 | size])
        elif size <= 65535:
            prefix += bytes([0x80 | 126]) + struct.pack("!H", size)
        else:
            prefix += bytes([0x80 | 127]) + struct.pack("!Q", size)
        self.socket.sendall(prefix + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send(self, value):
        self.frame(1, json.dumps(value, ensure_ascii=False).encode())

    def receive(self, timeout=8):
        deadline = time.monotonic() + timeout
        message = bytearray()
        started = False
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("WS message deadline")
            self.socket.settimeout(remaining)
            head = self.read(2)
            opcode = head[0] & 15
            size = head[1] & 127
            if head[1] & 128:
                raise ValueError("server frame must be unmasked")
            if size == 126:
                size = struct.unpack("!H", self.read(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self.read(8))[0]
            if size + len(message) > 2 * 1024 * 1024:
                raise ValueError("oversized WS message")
            payload = self.read(size)
            if opcode == 8:
                raise Closed(struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else None)
            if opcode == 9:
                self.frame(10, payload)
                continue
            if opcode == 10:
                continue
            if opcode == 1 and not started:
                started = True
            elif opcode != 0 or not started:
                raise ValueError("unexpected WS opcode")
            message.extend(payload)
            if head[0] & 128:
                return json.loads(message)

    def until(self, predicate, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = self.receive(max(0.01, deadline - time.monotonic()))
            if predicate(value):
                return value
        raise TimeoutError("WS matching message deadline")
