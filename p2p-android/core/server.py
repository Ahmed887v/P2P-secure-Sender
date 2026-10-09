#!/usr/bin/env python3
import asyncio
import json
import logging
import secrets
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

from aiohttp import WSMsgType, web

from runtime import HTTP_PORT, ShareRuntime, normalize_id

HEARTBEAT_INTERVAL = 30
CLIENT_TIMEOUT = 120
MAX_PENDING = 100
BASE_DIR = Path(__file__).resolve().parent
RECEIVER_HTML = BASE_DIR / "receiver.html"

logger = logging.getLogger("p2p.server")


@dataclass
class ClientSession:
    ws: web.WebSocketResponse
    device_id: str
    mode: str
    ip: str
    public_key: Optional[str] = None
    last_seen: datetime = field(default_factory=datetime.now)


class SignalingServer:
    def __init__(self):
        self.clients: Dict[str, ClientSession] = {}
        self.pending: Dict[str, List[dict]] = {}

    async def cleanup_stale(self):
        while True:
            await asyncio.sleep(HEARTBEAT_INTERVAL)
            now = datetime.now()
            stale = [
                did for did, c in self.clients.items()
                if (now - c.last_seen).total_seconds() > CLIENT_TIMEOUT
            ]
            for did in stale:
                self.clients.pop(did, None)
            ShareRuntime.clients = len(self.clients)

    async def handle_ws(self, request: web.Request):
        ws = web.WebSocketResponse(heartbeat=0, max_msg_size=4 * 1024 * 1024)
        await ws.prepare(request)
        client: Optional[ClientSession] = None
        try:
            async for raw in ws:
                if raw.type != WSMsgType.TEXT:
                    continue
                try:
                    msg = json.loads(raw.data)
                    client = await self._dispatch(ws, client, msg, request)
                except json.JSONDecodeError:
                    logger.warning("Invalid JSON from %s", request.remote)
                except Exception as exc:
                    logger.exception("Error: %s", exc)
        except Exception:
            pass
        finally:
            if client and client.device_id in self.clients:
                if self.clients[client.device_id].ws is ws:
                    del self.clients[client.device_id]
            ShareRuntime.clients = len(self.clients)
        return ws

    async def _dispatch(self, ws, client, msg: dict, request: web.Request):
        mtype = msg.get("type")
        if mtype == "register":
            return await self._register(ws, msg, request)
        if client is None:
            return None
        if mtype == "ping":
            client.last_seen = datetime.now()
            await ws.send_str(json.dumps({"type": "pong"}))
        elif mtype == "resolve":
            await self._resolve(ws, msg)
        else:
            await self._route(client.device_id, msg, mtype)
        return client

    async def _register(self, ws, msg: dict, request: web.Request):
        mode = msg.get("mode", "unknown")
        device_id = normalize_id(msg.get("device_id") or secrets.token_hex(8))
        if device_id in self.clients:
            try:
                await self.clients[device_id].ws.close()
            except Exception:
                pass
        client = ClientSession(
            ws=ws,
            device_id=device_id,
            mode=mode,
            ip=request.remote or "unknown",
            public_key=msg.get("public_key"),
        )
        self.clients[device_id] = client
        ShareRuntime.clients = len(self.clients)
        ShareRuntime.log(f"ws register {device_id} clients={len(self.clients)}")
        await ws.send_str(json.dumps({"type": "registered", "device_id": device_id}))
        if device_id in self.pending:
            for qmsg in self.pending[device_id]:
                await ws.send_str(json.dumps(qmsg))
            del self.pending[device_id]
        return client

    async def _resolve(self, ws, msg: dict):
        tid = normalize_id(msg.get("target_id") or "")
        if tid in self.clients:
            t = self.clients[tid]
            await ws.send_str(json.dumps({
                "type": "resolved",
                "target_id": tid,
                "online": True,
                "ip": t.ip,
                "mode": t.mode,
                "public_key": t.public_key,
            }))
        else:
            await ws.send_str(json.dumps({"type": "resolved", "target_id": tid, "online": False}))

    async def _route(self, sender_id: str, msg: dict, mtype: str):
        tid = normalize_id(msg.get("target_id") or "")
        if not tid:
            return
        payload = {
            **{k: v for k, v in msg.items() if k not in ("target_id",)},
            "type": mtype,
            "from_id": sender_id,
        }
        if tid in self.clients:
            try:
                await self.clients[tid].ws.send_str(json.dumps(payload))
                if mtype not in ("chunk", "chunk_ack"):
                    ShareRuntime.log(f"ws fwd {mtype} {sender_id} -> {tid}")
            except Exception:
                if mtype not in ("chunk", "chunk_ack"):
                    self._enqueue(tid, payload)
        elif mtype not in ("chunk", "chunk_ack"):
            self._enqueue(tid, payload)
            ShareRuntime.log(f"ws queue {mtype} {sender_id} -> {tid}")

    def _enqueue(self, target_id: str, payload: dict):
        if target_id not in self.pending:
            self.pending[target_id] = []
        if len(self.pending[target_id]) < MAX_PENDING:
            self.pending[target_id].append(payload)


def _receiver_html() -> str:
    html = RECEIVER_HTML.read_text(encoding="utf-8")
    arr = json.dumps(ShareRuntime.ws_urls())
    return html.replace("const BACKUP_WS = [];", f"const BACKUP_WS = {arr};")


async def serve_receiver(_request: web.Request):
    if not RECEIVER_HTML.exists():
        return web.Response(status=404, text="receiver.html not found")
    headers = {
        "Cache-Control": "no-store",
        "Access-Control-Allow-Origin": "*",
        "ngrok-skip-browser-warning": "1",
    }
    return web.Response(text=_receiver_html(), content_type="text/html", charset="utf-8", headers=headers)


async def serve_tunnels(_request: web.Request):
    body = json.dumps({"ws": ShareRuntime.ws_urls(), "link": ShareRuntime.receiver_link})
    return web.Response(
        text=body,
        content_type="application/json",
        headers={"Cache-Control": "no-store", "Access-Control-Allow-Origin": "*"},
    )


async def serve_options(_request: web.Request):
    return web.Response(
        status=200,
        headers={
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "*",
            "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
        },
    )


async def on_startup(app: web.Application):
    app["cleanup_task"] = asyncio.create_task(app["signaling"].cleanup_stale())


async def on_cleanup(app: web.Application):
    task = app.get("cleanup_task")
    if task:
        task.cancel()


def create_app() -> web.Application:
    signaling = SignalingServer()
    app = web.Application()
    app["signaling"] = signaling
    app.router.add_route("OPTIONS", "/{path:.*}", serve_options)
    app.router.add_get("/", serve_receiver)
    app.router.add_get("/receiver.html", serve_receiver)
    app.router.add_get("/tunnels.json", serve_tunnels)
    app.router.add_get("/api/tunnels", serve_tunnels)
    app.router.add_get("/ws", signaling.handle_ws)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    return app


class ServerRunner:
    def __init__(self, port: int = HTTP_PORT):
        self.port = port
        self.runner = None

    async def start(self):
        app = create_app()
        self.runner = web.AppRunner(app, access_log=None)
        await self.runner.setup()
        site = web.TCPSite(self.runner, "0.0.0.0", self.port)
        await site.start()
        logger.info("HTTP server on http://0.0.0.0:%d", self.port)

    async def stop(self):
        if self.runner:
            await self.runner.cleanup()
            self.runner = None


async def run():
    server = ServerRunner()
    await server.start()
    try:
        await asyncio.Future()
    except asyncio.CancelledError:
        pass
    finally:
        await server.stop()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass
