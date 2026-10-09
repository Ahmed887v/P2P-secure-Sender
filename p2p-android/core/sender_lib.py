#!/usr/bin/env python3
import asyncio
import base64
import hashlib
import json
import secrets
import time
from pathlib import Path
from typing import Callable, Optional

import websockets

from crypto_util import Crypto
from runtime import ShareRuntime, normalize_id

CHUNK_SIZE = 49152
RETRY_BASE = 1.0
RETRY_MAX = 60.0
RETRY_ATTEMPTS = 10
WINDOW = 32
BLOCKED = {".exe", ".dll", ".bat", ".cmd", ".sh", ".ps1", ".vbs", ".js", ".jar", ".apk", ".ipa", ".scr"}


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Rejected(Exception):
    pass


class Sender:
    def __init__(
        self,
        file_path: str,
        target: str,
        relay: str,
        on_status: Optional[Callable[[str], None]] = None,
        on_progress: Optional[Callable[[float, str], None]] = None,
    ):
        self.file = Path(file_path)
        self.target = normalize_id(target)
        self.relay = relay or ShareRuntime.local_relay()
        self.ws = None
        self.crypto = Crypto()
        self.total = 0
        self.size = self.file.stat().st_size
        self.on_status = on_status or (lambda _m: None)
        self.on_progress = on_progress or (lambda _p, _t: None)
        self.acks = {}
        self.ack_at = {}
        self.accepted = False
        self.got_key = False
        self.file_hash = ""
        self._fh = None
        self.sid = secrets.token_hex(8)
        if not self.file.exists() or not self.file.is_file():
            raise FileNotFoundError(str(file_path))
        if self.file.suffix.lower() in BLOCKED:
            raise ValueError(self.file.suffix)

    def _file_hash(self) -> str:
        h = hashlib.sha256()
        with open(self.file, "rb") as f:
            while c := f.read(CHUNK_SIZE):
                h.update(c)
        return h.hexdigest()

    async def _send(self, payload: dict):
        await self.ws.send(json.dumps(payload))

    async def _drop_socket(self):
        if self.ws is None:
            return
        try:
            await self.ws.close()
        except Exception:
            pass
        self.ws = None
        self.ack_at.clear()

    async def _ensure_socket(self):
        ws = self.ws
        if ws is not None and not getattr(ws, "closed", True):
            return
        await self._drop_socket()
        await self.connect()

    async def connect(self):
        delay = RETRY_BASE
        for i in range(RETRY_ATTEMPTS):
            try:
                headers = {"ngrok-skip-browser-warning": "1"}
                try:
                    self.ws = await websockets.connect(
                        self.relay,
                        additional_headers=headers,
                        max_size=2 * 1024 * 1024,
                        ping_interval=None,
                    )
                except TypeError:
                    self.ws = await websockets.connect(
                        self.relay,
                        extra_headers=headers,
                        max_size=2 * 1024 * 1024,
                        ping_interval=None,
                    )
                await self._send({
                    "type": "register",
                    "mode": "sender",
                    "device_id": self.sid,
                    "public_key": self.crypto.pub_jwk(),
                })
                resp = json.loads(await self.ws.recv())
                if resp.get("type") == "registered":
                    self.on_status("connected")
                    await self._send({
                        "type": "public_key",
                        "target_id": self.target,
                        "public_key": self.crypto.pub_jwk(),
                    })
                    return
            except Exception as exc:
                self.on_status(f"retry {i + 1}: {exc}")
                if i == RETRY_ATTEMPTS - 1:
                    raise ConnectionError(str(exc))
                await asyncio.sleep(delay)
                delay = min(delay * 2, RETRY_MAX)

    async def request_transfer(self) -> bool:
        size = self.size
        self.total = (size + CHUNK_SIZE - 1) // CHUNK_SIZE if size else 0
        pub = self.crypto.pub_jwk()

        async def send_request():
            await self._send({
                "type": "transfer_request",
                "target_id": self.target,
                "filename": self.file.name,
                "file_size": size,
                "total_chunks": self.total,
                "chunk_size": CHUNK_SIZE,
                "file_hash": self.file_hash or self._file_hash(),
                "public_key": pub,
            })

        await send_request()
        if self.accepted:
            drain_end = time.time() + 4
            while time.time() < drain_end:
                try:
                    raw = await asyncio.wait_for(self.ws.recv(), timeout=0.2)
                except asyncio.TimeoutError:
                    continue
                msg = json.loads(raw)
                mtype = msg.get("type")
                if mtype == "resume":
                    self._apply_resume(msg)
                    nxt = next((i for i in range(self.total) if not self.acks.get(i)), self.total)
                    await self._send({"type": "resume_ack", "target_id": self.target, "next": nxt})
                    return True
                if mtype == "chunk_ack":
                    self.acks[int(msg.get("index", -1))] = True
                elif mtype == "chunk_nack":
                    self.acks.pop(int(msg.get("index", -1)), None)
                elif mtype == "public_key" and not self.got_key:
                    self.crypto.derive(msg.get("public_key"))
                    self.got_key = True
                elif mtype == "transfer_response":
                    if not msg.get("accepted"):
                        raise Rejected()
                    self.accepted = True
            return True
        self.on_status("waiting")
        ShareRuntime.phase = "waiting"
        wait_end = time.time() + 180
        last_ask = time.time()
        while not self.accepted:
            if time.time() > wait_end:
                raise TimeoutError("timeout")
            try:
                raw = await asyncio.wait_for(self.ws.recv(), timeout=3)
            except asyncio.TimeoutError:
                if time.time() - last_ask > 8:
                    await send_request()
                    last_ask = time.time()
                continue
            msg = json.loads(raw)
            mtype = msg.get("type")
            if mtype == "public_key":
                if not self.got_key:
                    self.crypto.derive(msg.get("public_key"))
                    self.got_key = True
            elif mtype == "transfer_response":
                if not msg.get("accepted"):
                    self.on_status("rejected")
                    raise Rejected()
                if msg.get("public_key") and not self.got_key:
                    self.crypto.derive(msg.get("public_key"))
                    self.got_key = True
                if self.got_key:
                    self.accepted = True
            elif mtype == "resume":
                self._apply_resume(msg)
                nxt = next((i for i in range(self.total) if not self.acks.get(i)), self.total)
                await self._send({"type": "resume_ack", "target_id": self.target, "next": nxt})
            elif mtype in ("pong",):
                continue
        if not self.got_key:
            raise RuntimeError("no_key")
        self.on_status("accepted")
        return True

    def _apply_resume(self, msg: dict):
        bits = msg.get("have_bits")
        if bits:
            try:
                raw = base64.b64decode(bits)
                for i in range(self.total):
                    on = i < len(raw) * 8 and (raw[i >> 3] & (1 << (i & 7))) != 0
                    if on:
                        self.acks[i] = True
                    else:
                        self.acks.pop(i, None)
            except Exception:
                pass
        for i in msg.get("have") or []:
            self.acks[int(i)] = True
        for i in msg.get("missing") or []:
            self.acks.pop(int(i), None)
        ShareRuntime.log(f"resume have={len(msg.get('have') or [])} missing={len(msg.get('missing') or [])}")

    def _read_chunk(self, index: int) -> bytes:
        offset = index * CHUNK_SIZE
        size = self.file.stat().st_size
        if offset >= size:
            return b""
        if self._fh is None:
            self._fh = open(self.file, "rb")
        self._fh.seek(offset)
        return self._fh.read(CHUNK_SIZE)

    async def _send_chunk(self, index: int):
        chunk = self._read_chunk(index)
        if not chunk:
            return
        nonce = secrets.token_bytes(12)
        enc = self.crypto.encrypt(chunk, nonce)
        self.ack_at[index] = time.time()
        offset = index * CHUNK_SIZE
        size = self.file.stat().st_size
        await self._send({
            "type": "chunk",
            "target_id": self.target,
            "index": index,
            "data": base64.b64encode(enc).decode(),
            "nonce": base64.b64encode(nonce).decode(),
            "chunk_hash": _sha256(chunk),
            "is_last": offset + len(chunk) >= size,
        })

    async def send_file(self):
        size = self.file.stat().st_size
        start = time.time()
        ShareRuntime.phase = "sending"
        ShareRuntime.file_name = self.file.name
        ShareRuntime.file_size = size
        self.on_status("sending")
        while True:
            missing = [i for i in range(self.total) if not self.acks.get(i)]
            if not missing:
                pct = 100.0
                elapsed = max(time.time() - start, 0.001)
                ShareRuntime.speed_bps = size / elapsed
                ShareRuntime.sent_bytes = size
                self.on_progress(pct, f"100% | {ShareRuntime.speed_text()}")
                break
            done = self.total - len(missing)
            ShareRuntime.sent_bytes = min(done * CHUNK_SIZE, size)
            pct = (done * 100) / self.total
            elapsed = max(time.time() - start, 0.001)
            ShareRuntime.speed_bps = ShareRuntime.sent_bytes / elapsed
            self.on_progress(pct, f"{pct:.1f}% | {ShareRuntime.speed_text()}")
            in_flight = sum(1 for i in missing if i in self.ack_at and not self.acks.get(i))
            sent_now = 0
            now = time.time()
            for idx in missing:
                if sent_now + max(in_flight, 0) >= WINDOW:
                    break
                last = self.ack_at.get(idx)
                if last is not None and now - last < 2.5:
                    continue
                if last is not None:
                    ShareRuntime.acks_retry += 1
                await self._send_chunk(idx)
                sent_now += 1
            deadline = time.time() + 3.5
            while time.time() < deadline:
                try:
                    raw = await asyncio.wait_for(self.ws.recv(), timeout=0.08)
                except asyncio.TimeoutError:
                    continue
                msg = json.loads(raw)
                mtype = msg.get("type")
                if mtype == "chunk_ack":
                    idx = int(msg.get("index", -1))
                    self.acks[idx] = True
                    sent_at = self.ack_at.pop(idx, None)
                    if sent_at is not None:
                        ShareRuntime.rtt_ms = int((time.time() - sent_at) * 1000)
                        ShareRuntime.acks_ok += 1
                elif mtype == "chunk_nack":
                    idx = int(msg.get("index", -1))
                    self.acks.pop(idx, None)
                    ShareRuntime.acks_retry += 1
                    await self._send_chunk(idx)
                elif mtype == "resume":
                    self._apply_resume(msg)
                    nxt = next((i for i in range(self.total) if not self.acks.get(i)), self.total)
                    await self._send({"type": "resume_ack", "target_id": self.target, "next": nxt})
                elif mtype == "public_key" and not self.got_key:
                    self.crypto.derive(msg.get("public_key"))
                    self.got_key = True
                elif mtype == "transfer_response":
                    if not msg.get("accepted"):
                        raise Rejected()
                    self.accepted = True
                if all(self.acks.get(i) for i in range(self.total)):
                    break
        await self._send({
            "type": "transfer_complete",
            "target_id": self.target,
            "final_hash": self.file_hash,
        })
        ShareRuntime.phase = "done"
        ShareRuntime.sent_bytes = size
        self.on_status("done")

    async def run(self):
        if len(self.target) < 8:
            raise ValueError("bad_id")
        if self.size <= 0:
            raise ValueError("empty_file")
        ShareRuntime.reset_stats()
        ShareRuntime.phase = "hashing"
        ShareRuntime.file_name = self.file.name
        ShareRuntime.file_size = self.size
        self.file_hash = self._file_hash()
        last_progress = time.time()
        last_bytes = 0
        try:
            while True:
                try:
                    ShareRuntime.phase = "connecting"
                    await self._ensure_socket()
                    await self.request_transfer()
                    ShareRuntime.phase = "sending"
                    await self.send_file()
                    return
                except Rejected:
                    ShareRuntime.phase = "rejected"
                    self.on_status("rejected")
                    return
                except Exception as exc:
                    ShareRuntime.phase = "resume"
                    ShareRuntime.log(f"sender drop {exc} — resume")
                    self.on_status("resume")
                    await self._drop_socket()
                    if time.time() - last_progress > 180 and not self.acks:
                        raise
                    await asyncio.sleep(0.9)
                finally:
                    now = time.time()
                    db = ShareRuntime.sent_bytes - last_bytes
                    if db > 0:
                        last_progress = now
                        last_bytes = ShareRuntime.sent_bytes
        finally:
            if self._fh:
                try:
                    self._fh.close()
                except Exception:
                    pass
                self._fh = None
            await self._drop_socket()
