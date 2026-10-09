#!/usr/bin/env python3
import base64
import json
from typing import Optional

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


class Crypto:
    def __init__(self):
        self.priv = ec.generate_private_key(ec.SECP256R1())
        self.pub = self.priv.public_key()
        self.aes: Optional[AESGCM] = None

    @staticmethod
    def _b64url(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

    @staticmethod
    def _b64url_decode(text: str) -> bytes:
        pad = "=" * ((4 - len(text) % 4) % 4)
        return base64.urlsafe_b64decode(text + pad)

    def pub_jwk(self) -> str:
        nums = self.pub.public_numbers()
        return json.dumps({
            "kty": "EC",
            "crv": "P-256",
            "x": self._b64url(nums.x.to_bytes(32, "big")),
            "y": self._b64url(nums.y.to_bytes(32, "big")),
        })

    def derive(self, peer_jwk):
        if isinstance(peer_jwk, str):
            peer_jwk = json.loads(peer_jwk)
        x = int.from_bytes(self._b64url_decode(peer_jwk["x"]), "big")
        y = int.from_bytes(self._b64url_decode(peer_jwk["y"]), "big")
        peer = ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()
        shared = self.priv.exchange(ec.ECDH(), peer)
        key = HKDF(
            algorithm=hashes.SHA256(),
            length=32,
            salt=b"\x00" * 32,
            info=b"p2p-v1",
        ).derive(shared)
        self.aes = AESGCM(key)

    def encrypt(self, data: bytes, nonce: bytes) -> bytes:
        if self.aes is None:
            raise RuntimeError("encryption key is not ready")
        return self.aes.encrypt(nonce, data, None)
