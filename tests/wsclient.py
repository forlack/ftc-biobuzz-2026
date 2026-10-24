"""Tiny dependency-free websocket client for testing the dashboard server."""
import base64
import json
import os
import socket


class WebSocket:
    def __init__(self, host='127.0.0.1', port=8686, path='/ws', timeout=5):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f'GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n'
                           f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n'
                           'Sec-WebSocket-Version: 13\r\n\r\n').encode())
        head = b''
        while b'\r\n\r\n' not in head:
            head += self.sock.recv(1)
        if b' 101 ' not in head.split(b'\r\n')[0]:
            raise RuntimeError(head.decode(errors='replace'))

    def _read(self, count):
        data = b''
        while len(data) < count:
            chunk = self.sock.recv(count - len(data))
            if not chunk:
                raise ConnectionError('closed')
            data += chunk
        return data

    def receive(self):
        first, second = self._read(2)
        length = second & 0x7f
        if length == 126:
            length = int.from_bytes(self._read(2), 'big')
        elif length == 127:
            length = int.from_bytes(self._read(8), 'big')
        payload = self._read(length)
        return json.loads(payload) if first & 0x0f == 1 else None

    def close(self):
        try:
            self.sock.sendall(bytes([0x88, 0x80, 0, 0, 0, 0]))
        finally:
            self.sock.close()
