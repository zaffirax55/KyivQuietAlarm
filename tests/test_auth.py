import hashlib
import hmac
import secrets
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "windows"))
import agent

class AuthenticationTests(unittest.TestCase):
    def setUp(self):
        agent.State.secret = secrets.token_hex(32)
        agent.used_nonces.clear()

    def headers(self, method, path, body, timestamp=None, nonce=None):
        timestamp = timestamp or str(int(time.time() * 1000))
        nonce = nonce or secrets.token_hex(16)
        body_hash = hashlib.sha256(body).hexdigest()
        text = f"{method}\n{path}\n{timestamp}\n{nonce}\n{body_hash}".encode()
        return {"X-PC-Time": timestamp, "X-PC-Nonce": nonce,
                "X-PC-Signature": hmac.new(bytes.fromhex(agent.State.secret), text, hashlib.sha256).hexdigest()}

    def test_valid_hmac(self):
        body = b'{"action":"lock"}'
        h = self.headers("POST", "/v1/action", body)
        self.assertTrue(agent.verify("POST", "/v1/action", body, h))

    def test_wrong_payload(self):
        h = self.headers("POST", "/v1/action", b'{"action":"volume_up"}')
        self.assertFalse(agent.verify("POST", "/v1/action", b'{"action":"shutdown"}', h))

    def test_replay_rejected(self):
        h = self.headers("GET", "/v1/status", b"")
        self.assertTrue(agent.verify("GET", "/v1/status", b"", h))
        self.assertFalse(agent.verify("GET", "/v1/status", b"", h))

    def test_expired_rejected(self):
        h = self.headers("GET", "/v1/status", b"", str(int((time.time() - 300) * 1000)))
        self.assertFalse(agent.verify("GET", "/v1/status", b"", h))

    def test_wrong_key_rejected(self):
        h = self.headers("GET", "/v1/status", b"")
        agent.State.secret = secrets.token_hex(32)
        self.assertFalse(agent.verify("GET", "/v1/status", b"", h))

if __name__ == '__main__': unittest.main()
