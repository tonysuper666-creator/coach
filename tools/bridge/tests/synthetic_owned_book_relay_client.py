"""Test-only loopback relay client. Never use for real books."""
import socket
import sys

request = sys.stdin.buffer.read(1_048_577)
if len(request) > 1_048_576:
    raise ValueError("request too large")
with socket.create_connection(("127.0.0.1", 38273), timeout=120) as peer:
    peer.sendall(request + b"\n")
    with peer.makefile("rb") as reply:
        result = reply.readline(1_048_577)
if not result or len(result) > 1_048_576:
    raise ValueError("missing or oversized response")
sys.stdout.buffer.write(result)
