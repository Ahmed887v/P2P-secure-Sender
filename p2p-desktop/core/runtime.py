import re
from collections import deque
from typing import List

HTTP_PORT = 8080
HEX = set("0123456789abcdef")


def normalize_id(raw: str) -> str:
    s = "".join(c for c in (raw or "").lower() if c in HEX)
    return s[:16]


class ShareRuntime:
    receiver_link = ""
    last_error = ""
    _ws_urls: List[str] = []
    _rows: list = []
    logs = deque(maxlen=80)
    phase = ""
    speed_bps = 0.0
    rtt_ms = 0
    acks_ok = 0
    acks_retry = 0
    sent_bytes = 0
    file_name = ""
    file_size = 0
    clients = 0

    @classmethod
    def local_relay(cls) -> str:
        return f"ws://127.0.0.1:{HTTP_PORT}/ws"

    @classmethod
    def ws_urls(cls) -> List[str]:
        return list(cls._ws_urls)

    @classmethod
    def set_ws_urls(cls, urls):
        out = []
        for u in urls:
            if u and u not in out:
                out.append(u)
        cls._ws_urls = out

    @classmethod
    def tunnel_rows(cls) -> list:
        return list(cls._rows)

    @classmethod
    def set_rows(cls, rows):
        cls._rows = list(rows)

    @classmethod
    def log(cls, msg: str):
        cls.logs.appendleft(msg)

    @classmethod
    def reset_stats(cls):
        cls.phase = ""
        cls.speed_bps = 0.0
        cls.rtt_ms = 0
        cls.acks_ok = 0
        cls.acks_retry = 0
        cls.sent_bytes = 0
        cls.file_name = ""
        cls.file_size = 0

    @classmethod
    def speed_text(cls) -> str:
        b = cls.speed_bps
        if b >= 1024 * 1024:
            return f"{b / 1024 / 1024:.1f} MB/s"
        if b >= 1024:
            return f"{b / 1024:.1f} KB/s"
        return f"{b:.0f} B/s"


def is_tunnel_url(url: str) -> bool:
    try:
        from urllib.parse import urlparse
        host = (urlparse(url).hostname or "").lower()
        if not host:
            return False
        if host in REJECT_HOSTS:
            return False
        parts = host.split(".")
        if len(parts) < 3:
            return False
        if parts[0] in REJECT_LABELS:
            return False
        return (
            host.endswith(".serveousercontent.com")
            or host.endswith(".serveo.net")
            or host.endswith(".free.pinggy.net")
            or host.endswith(".pinggy.net")
            or host.endswith(".pinggy-free.link")
            or host.endswith(".run.pinggy-free.link")
            or host.endswith(".a.pinggy.io")
            or host.endswith(".pinggy.link")
            or host.endswith(".free.pinggy.link")
            or host.endswith(".lhr.life")
            or host.endswith(".localhost.run")
            or host.endswith(".ngrok-free.app")
            or host.endswith(".ngrok-free.dev")
            or host.endswith(".ngrok.app")
            or host.endswith(".ngrok.io")
        )
    except Exception:
        return False


REJECT_HOSTS = {
    "dashboard.pinggy.io",
    "console.serveo.net",
    "www.pinggy.io",
    "pinggy.io",
    "docs.pinggy.io",
    "status.pinggy.io",
    "pinggy.link",
    "www.localhost.run",
    "localhost.run",
    "ssh.localhost.run",
    "www.serveo.net",
    "serveo.net",
    "status.serveo.net",
    "api.serveo.net",
    "www.ssh.com",
    "ssh.com",
    "127.0.0.1",
    "localhost",
}

REJECT_LABELS = {
    "www", "console", "dashboard", "docs", "status", "api", "admin", "app", "mail",
}

URL_RE = re.compile(r"https?://[a-zA-Z0-9._\-]+")


def extract_url(text: str) -> str:
    for raw in URL_RE.findall(text or ""):
        raw = raw.rstrip("/")
        url = "https://" + raw[len("http://"):] if raw.startswith("http://") else raw
        if is_tunnel_url(url):
            return url
    return ""
