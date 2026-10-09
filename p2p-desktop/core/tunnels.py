import socket
import threading
import time
from dataclasses import dataclass

from runtime import ShareRuntime, extract_url, is_tunnel_url

HTTP_PORT = 8080


@dataclass
class TunnelTarget:
    name: str
    user: str
    host: str
    port: int
    remote_port: int


PROVIDERS = [
    TunnelTarget("serveo", "serveo", "serveo.net", 22, 80),
    TunnelTarget("pinggy", "a", "a.pinggy.io", 443, 0),
    TunnelTarget("lhr", "nokey", "localhost.run", 22, 80),
    TunnelTarget("lhr2", "nokey", "ssh.localhost.run", 22, 80),
]


def _pump(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try:
            src.close()
        except Exception:
            pass
        try:
            dst.close()
        except Exception:
            pass


def _forward(chan, host, port):
    try:
        sock = socket.create_connection((host, port), timeout=20)
    except Exception:
        try:
            chan.close()
        except Exception:
            pass
        return
    t1 = threading.Thread(target=_pump, args=(chan, sock), daemon=True)
    t2 = threading.Thread(target=_pump, args=(sock, chan), daemon=True)
    t1.start()
    t2.start()


class SshTunnel:
    def __init__(self, target: TunnelTarget):
        self.target = target
        self.public_url = ""
        self.last_error = ""
        self.started_at = 0
        self.rtt_ms = -1
        self._client = None
        self._transport = None
        self._shell = None
        self._running = False
        self._local_port = HTTP_PORT
        self._watch = None
        self._accept = None

    def is_alive(self) -> bool:
        t = self._transport
        return bool(
            t
            and t.is_active()
            and self.public_url
            and is_tunnel_url(self.public_url)
        )

    def start(self, local_port: int) -> str:
        if self.is_alive():
            ShareRuntime.log(f"reuse {self.target.name} {self.public_url}")
            return self.public_url
        self.stop()
        self.last_error = ""
        self.public_url = ""
        self._local_port = local_port
        ShareRuntime.log(f"try {self.target.name} {self.target.user}@{self.target.host}:{self.target.port}")
        url = self._try_one(local_port)
        if url and is_tunnel_url(url):
            self.public_url = url.rstrip("/")
            self.last_error = ""
            self.started_at = int(time.time() * 1000)
            ShareRuntime.log(f"tunnel ready {self.target.name} {self.public_url}")
            self._start_watchdog()
            return self.public_url
        ShareRuntime.log(f"skip {self.target.name} err={self.last_error}")
        self._disconnect()
        if not self.last_error:
            self.last_error = f"no_url_{self.target.name}"
        return ""

    def stop(self):
        self._running = False
        self._disconnect()
        self.public_url = ""

    def _disconnect(self):
        self._running = False
        try:
            if self._shell:
                self._shell.close()
        except Exception:
            pass
        try:
            if self._client:
                self._client.close()
        except Exception:
            pass
        self._shell = None
        self._transport = None
        self._client = None

    def _try_one(self, local_port: int) -> str:
        try:
            import paramiko
        except ImportError:
            self.last_error = "paramiko_missing"
            return ""
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            client.connect(
                self.target.host,
                port=self.target.port,
                username=self.target.user,
                password="",
                look_for_keys=False,
                allow_agent=False,
                timeout=16,
                banner_timeout=16,
                auth_timeout=16,
            )
        except Exception as exc:
            try:
                sock = socket.create_connection((self.target.host, self.target.port), timeout=16)
                trans = paramiko.Transport(sock)
                trans.banner_timeout = 16
                trans.start_client(timeout=16)
                try:
                    trans.auth_none(self.target.user)
                except Exception:
                    try:
                        trans.auth_password(self.target.user, "")
                    except Exception:
                        pass
                if not trans.is_authenticated() and not trans.is_active():
                    raise exc
                client._transport = trans
            except Exception as e2:
                self.last_error = str(e2 or exc)[:120]
                ShareRuntime.log(f"tryOne {self.target.name} failed {self.last_error}")
                try:
                    client.close()
                except Exception:
                    pass
                return ""
        transport = client.get_transport()
        if transport is None:
            self.last_error = f"no_transport_{self.target.name}"
            try:
                client.close()
            except Exception:
                pass
            return ""
        transport.set_keepalive(12)
        remote_port = self.target.remote_port
        try:
            got = transport.request_port_forward("", remote_port)
            if remote_port == 0 and got:
                remote_port = got
        except Exception as exc:
            ShareRuntime.log(f"rforward {self.target.name} {exc}")
            try:
                transport.request_port_forward("", remote_port)
            except Exception as e2:
                self.last_error = str(e2)[:120]
                try:
                    client.close()
                except Exception:
                    pass
                return ""
        self._client = client
        self._transport = transport
        self._running = True
        self._accept = threading.Thread(target=self._accept_loop, daemon=True, name=f"p2p-acc-{self.target.name}")
        self._accept.start()
        url, shell, banner = self._capture_url(transport)
        self._shell = shell
        ShareRuntime.log(f"banner {self.target.name}={(banner or '').replace(chr(10), ' ')[:240]}")
        ShareRuntime.log(f"extracted {self.target.name}={url}")
        if not url or not is_tunnel_url(url):
            self.last_error = f"no_url_{self.target.name}"
            self._disconnect()
            return ""
        return url.rstrip("/")

    def _accept_loop(self):
        while self._running:
            try:
                trans = self._transport
                if trans is None or not trans.is_active():
                    break
                chan = trans.accept(1.0)
                if chan is None:
                    continue
                threading.Thread(
                    target=_forward,
                    args=(chan, "127.0.0.1", self._local_port),
                    daemon=True,
                ).start()
            except Exception:
                if not self._running:
                    break
                time.sleep(0.3)

    def _capture_url(self, transport):
        shell = None
        text = ""
        url = ""
        try:
            shell = transport.open_session()
            try:
                shell.get_pty()
            except Exception:
                pass
            shell.invoke_shell()
            end = time.time() + 22
            buf = b""
            while time.time() < end and transport.is_active():
                if shell.recv_ready():
                    chunk = shell.recv(4096)
                    if chunk:
                        buf += chunk
                text = buf.decode("utf-8", "replace")
                url = extract_url(text)
                if url:
                    break
                time.sleep(0.25)
            if not url:
                text = buf.decode("utf-8", "replace")
                url = extract_url(text)
        except Exception as exc:
            ShareRuntime.log(f"capture {self.target.name} {exc}")
        return url, shell, text

    def _start_watchdog(self):
        def watch():
            while self._running:
                try:
                    trans = self._transport
                    if trans is None or not trans.is_active():
                        ShareRuntime.log(f"tunnel dropped {self.target.name}")
                        self.public_url = ""
                        self.last_error = "tunnel_dropped"
                        break
                    shell = self._shell
                    if shell is not None:
                        try:
                            if shell.recv_ready():
                                t = shell.recv(4096).decode("utf-8", "replace")
                                ShareRuntime.log(f"ssh {self.target.name} {t.replace(chr(10), ' ')[:120]}")
                                low = t.lower()
                                if any(x in low for x in ("closed", "expired", "offline", "denied")):
                                    self.public_url = ""
                                    self.last_error = "tunnel_closed"
                                    try:
                                        trans.close()
                                    except Exception:
                                        pass
                                    break
                        except Exception:
                            pass
                    t0 = time.perf_counter()
                    try:
                        trans.send_ignore()
                        self.rtt_ms = max(int((time.perf_counter() - t0) * 1000), 1)
                    except Exception:
                        pass
                    time.sleep(4)
                except Exception as exc:
                    ShareRuntime.log(f"watch {self.target.name} {exc}")
                    self.public_url = ""
                    self.last_error = "tunnel_dropped"
                    break
        self._watch = threading.Thread(target=watch, daemon=True, name=f"p2p-watch-{self.target.name}")
        self._watch.start()


class TunnelPool:
    def __init__(self):
        self._tunnels = []
        self._lock = threading.Lock()
        self._connecting = set()
        self.primary_name = ""

    def alive(self):
        with self._lock:
            return [t for t in self._tunnels if t.is_alive()]

    def alive_urls(self):
        out = []
        for t in self.alive():
            if t.public_url not in out:
                out.append(t.public_url)
        return out

    def failover(self):
        live = self.alive()
        if not live:
            self.primary_name = ""
            return None
        keep = next((t for t in live if t.target.name == self.primary_name), None)
        if keep:
            return keep
        pick = min(live, key=lambda t: t.rtt_ms if t.rtt_ms > 0 else 10**9)
        ShareRuntime.log(f"failover {self.primary_name or '-'} -> {pick.target.name}")
        self.primary_name = pick.target.name
        return pick

    def primary(self):
        live = self.alive()
        keep = next((t for t in live if t.target.name == self.primary_name), None)
        if keep:
            return keep
        return self.failover()

    def primary_url(self) -> str:
        p = self.primary()
        return p.public_url if p else ""

    def is_alive(self) -> bool:
        p = self.primary()
        return bool(p and p.is_alive())

    def last_error(self) -> str:
        with self._lock:
            for t in self._tunnels:
                if t.last_error:
                    return t.last_error
        return ""

    def snapshot(self):
        now = int(time.time() * 1000)
        with self._lock:
            live = {t.target.name: t for t in self._tunnels}
        rows = []
        for p in PROVIDERS:
            t = live.get(p.name)
            if t is not None and t.is_alive():
                rows.append({
                    "name": p.name,
                    "state": "up",
                    "url": t.public_url,
                    "primary": p.name == self.primary_name,
                    "age_sec": ((now - t.started_at) // 1000) if t.started_at else 0,
                    "rtt_ms": t.rtt_ms,
                })
            elif p.name in self._connecting:
                rows.append({"name": p.name, "state": "opening", "url": "", "primary": False, "age_sec": 0, "rtt_ms": -1})
            else:
                rows.append({"name": p.name, "state": "down", "url": "", "primary": False, "age_sec": 0, "rtt_ms": -1})
        return rows

    def ensure(self, local_port: int) -> str:
        self._prune()
        self._start_missing(local_port)
        deadline = time.time() + 28
        while time.time() < deadline:
            url = self.primary_url()
            if url:
                ShareRuntime.log(f"pool primary={self.primary_name} urls={self.alive_urls()}")
                return url
            time.sleep(0.3)
        url = self.primary_url()
        ShareRuntime.log(f"pool wait done primary={self.primary_name} urls={self.alive_urls()}")
        return url

    def refresh(self, local_port: int) -> str:
        self._prune()
        self._start_missing(local_port)
        return self.primary_url()

    def stop(self):
        with self._lock:
            tunnels = list(self._tunnels)
            self._tunnels.clear()
        for t in tunnels:
            try:
                t.stop()
            except Exception:
                pass
        self._connecting.clear()
        self.primary_name = ""

    def _prune(self):
        with self._lock:
            dead = [t for t in self._tunnels if not t.is_alive()]
            for t in dead:
                ShareRuntime.log(f"pool drop {t.target.name}")
                try:
                    t.stop()
                except Exception:
                    pass
                self._tunnels.remove(t)
                self._connecting.discard(t.target.name)
                if self.primary_name == t.target.name:
                    self.primary_name = ""

    def _start_missing(self, local_port: int):
        live_names = {t.target.name for t in self.alive()}
        for p in PROVIDERS:
            if p.name in live_names:
                continue
            if p.name in self._connecting:
                continue
            self._connecting.add(p.name)

            def open_one(prov=p):
                try:
                    jt = SshTunnel(prov)
                    url = jt.start(local_port)
                    if url:
                        with self._lock:
                            self._tunnels.append(jt)
                        ShareRuntime.log(f"pool add {prov.name} {url} n={len(self.alive())}")
                    else:
                        try:
                            jt.stop()
                        except Exception:
                            pass
                except Exception as exc:
                    ShareRuntime.log(f"pool {prov.name} {exc}")
                finally:
                    self._connecting.discard(prov.name)

            threading.Thread(target=open_one, daemon=True, name=f"p2p-open-{p.name}").start()
