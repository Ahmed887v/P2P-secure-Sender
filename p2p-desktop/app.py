#!/usr/bin/env python3
import asyncio
import os
import platform
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path
from tkinter import filedialog, messagebox
import tkinter as tk
from tkinter import ttk

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "core"))

from bootstrap import configure_ngrok, ensure_ready, load_config, ngrok_path, save_config
from i18n.strings import STRINGS, THEMES
from runtime import HTTP_PORT, ShareRuntime, is_tunnel_url, normalize_id


class NgrokTunnel:
    def __init__(self):
        self.proc = None
        self.public_url = ""

    def start(self, token=""):
        binary = ngrok_path()
        if not binary:
            return ""
        if token:
            configure_ngrok(token)
        self.stop()
        self.proc = subprocess.Popen(
            [str(binary), "http", str(HTTP_PORT), "--log=stdout"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        for _ in range(40):
            time.sleep(0.4)
            url = self._api_url()
            if url:
                self.public_url = url
                return url
        return ""

    def _api_url(self):
        try:
            import json
            import urllib.request
            with urllib.request.urlopen("http://127.0.0.1:4040/api/tunnels", timeout=1) as resp:
                data = json.loads(resp.read().decode())
            for t in data.get("tunnels", []):
                url = t.get("public_url", "")
                if url.startswith("https://") and is_tunnel_url(url):
                    return url.rstrip("/")
        except Exception:
            return ""
        return ""

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
        self.proc = None


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.cfg = load_config()
        self.lang = self.cfg.get("language", "ar")
        self.theme_name = self.cfg.get("theme", "dark")
        self.ngrok = NgrokTunnel()
        self.pool = None
        self.server_thread = None
        self.loop = None
        self.file_path = ""
        self.sending = False
        self._build()
        self.after(200, self._first_run)
        self.after(1000, self._tick)

    def t(self, key):
        return STRINGS.get(self.lang, STRINGS["en"]).get(key, key)

    def colors(self):
        return THEMES.get(self.theme_name, THEMES["dark"])

    def _build(self):
        self.title(self.t("app_title"))
        self.geometry("620x860")
        self.minsize(520, 720)
        icon = ROOT / "assets" / "icon.png"
        if icon.exists():
            try:
                self.iconphoto(True, tk.PhotoImage(file=str(icon)))
            except Exception:
                pass
        self._apply_theme()
        self.columnconfigure(0, weight=1)
        bar = tk.Frame(self, bg=self.colors()["bg"])
        bar.grid(row=0, column=0, sticky="ew", padx=18, pady=(16, 0))
        tk.Label(bar, text=self.t("app_title"), font=("Segoe UI", 18, "bold"),
                 bg=self.colors()["bg"], fg=self.colors()["text"]).pack(side="left")
        tk.Button(bar, text=self.t("settings"), command=self.open_settings,
                  bg=self.colors()["card"], fg=self.colors()["text"], relief="flat").pack(side="right")

        card = tk.Frame(self, bg=self.colors()["card"], padx=18, pady=18)
        card.grid(row=1, column=0, sticky="nsew", padx=18, pady=12)
        self.rowconfigure(1, weight=1)
        card.columnconfigure(0, weight=1)

        tk.Label(card, text=self.t("share_link"), bg=self.colors()["card"],
                 fg=self.colors()["muted"]).grid(row=0, column=0, sticky="w")
        self.link_var = tk.StringVar(value="...")
        self.link_entry = tk.Entry(card, textvariable=self.link_var, relief="flat",
                                   font=("Consolas", 11), justify="center")
        self.link_entry.grid(row=1, column=0, sticky="ew", pady=(6, 10), ipady=8)
        btns = tk.Frame(card, bg=self.colors()["card"])
        btns.grid(row=2, column=0, sticky="ew")
        tk.Button(btns, text=self.t("copy"), command=self.copy_link,
                  bg=self.colors()["accent"], fg="white", relief="flat", padx=16, pady=8).pack(side="left")
        tk.Button(btns, text=self.t("share"), command=self.share_link,
                  bg=self.colors()["accent"], fg="white", relief="flat", padx=16, pady=8).pack(side="left", padx=8)

        tk.Label(card, text=self.t("receiver_id"), bg=self.colors()["card"],
                 fg=self.colors()["muted"]).grid(row=3, column=0, sticky="w", pady=(24, 0))
        self.id_var = tk.StringVar()
        tk.Entry(card, textvariable=self.id_var, relief="flat", font=("Consolas", 14),
                 justify="center").grid(row=4, column=0, sticky="ew", pady=(6, 10), ipady=10)

        self.file_var = tk.StringVar(value="")
        tk.Button(card, text=self.t("choose_file"), command=self.choose_file,
                  bg=self.colors()["accent"], fg="white", relief="flat", pady=12).grid(row=5, column=0, sticky="ew")
        tk.Label(card, textvariable=self.file_var, bg=self.colors()["card"],
                 fg=self.colors()["text"]).grid(row=6, column=0, sticky="w", pady=8)

        self.status_var = tk.StringVar(value=self.t("status_starting"))
        tk.Label(card, textvariable=self.status_var, wraplength=520, justify="center",
                 bg=self.colors()["card"], fg=self.colors()["text"], font=("Segoe UI", 12, "bold")).grid(
            row=7, column=0, pady=12)
        self.progress = ttk.Progressbar(card, maximum=100)
        self.progress.grid(row=8, column=0, sticky="ew")

        stay = tk.Label(card, text=self.t("stay_notice"), wraplength=520, justify="center",
                        bg=self.colors()["card"], fg="#ffda6a", font=("Segoe UI", 9))
        stay.grid(row=9, column=0, pady=(10, 0))

        act = tk.Frame(card, bg=self.colors()["card"])
        act.grid(row=10, column=0, pady=14)
        tk.Button(act, text=self.t("again"), command=self.reset_flow,
                  bg=self.colors()["card"], fg=self.colors()["text"], relief="groove", padx=16, pady=8).pack(side="left", padx=6)
        tk.Button(act, text=self.t("close"), command=self.on_close,
                  bg=self.colors()["card"], fg=self.colors()["text"], relief="groove", padx=16, pady=8).pack(side="left", padx=6)

        mon = tk.Frame(self, bg=self.colors()["card"], padx=14, pady=12)
        mon.grid(row=2, column=0, sticky="ew", padx=18, pady=(0, 16))
        tk.Label(mon, text=self.t("monitor"), bg=self.colors()["card"],
                 fg=self.colors()["text"], font=("Segoe UI", 11, "bold")).pack(anchor="w")
        self.mon_summary = tk.Label(mon, text="", bg=self.colors()["card"],
                                    fg=self.colors()["muted"], justify="left", font=("Consolas", 10))
        self.mon_summary.pack(anchor="w", pady=(4, 4))
        self.mon_tunnels = tk.Label(mon, text="", bg=self.colors()["card"],
                                    fg=self.colors()["text"], justify="left", font=("Consolas", 10))
        self.mon_tunnels.pack(anchor="w")
        self.mon_logs = tk.Text(mon, height=6, bg="#0d1117", fg="#8ea0c8", relief="flat",
                                font=("Consolas", 9), wrap="word")
        self.mon_logs.pack(fill="x", pady=(8, 0))
        self.mon_logs.configure(state="disabled")

        self.protocol("WM_DELETE_WINDOW", self.on_close)

    def _apply_theme(self):
        self.configure(bg=self.colors()["bg"])

    def _first_run(self):
        if not self.cfg.get("setup_done"):
            if messagebox.askyesno(self.t("setup_title"), self.t("setup_msg")):
                try:
                    ensure_ready(True)
                    self.cfg = load_config()
                except Exception as exc:
                    messagebox.showerror(self.t("setup_title"), str(exc))
            else:
                return
        threading.Thread(target=self.start_backend, daemon=True).start()

    def start_backend(self):
        self._set_status(self.t("status_starting"))
        self.loop = asyncio.new_event_loop()
        self.server_thread = threading.Thread(target=self._run_server, daemon=True)
        self.server_thread.start()
        time.sleep(1.0)
        from tunnels import TunnelPool
        self.pool = TunnelPool()
        public = self.pool.ensure(HTTP_PORT)
        token = self.cfg.get("ngrok_token", "")
        if ngrok_path() and token:
            nurl = self.ngrok.start(token)
            if nurl and is_tunnel_url(nurl):
                extras = list(self.pool.alive_urls())
                if nurl not in extras:
                    extras.append(nurl)
                if not public:
                    public = nurl
        self._publish(public)
        self.after(0, lambda: self._set_status(self.t("status_ready")))

    def _publish(self, public_base: str):
        urls = []
        if self.pool:
            urls.extend(self.pool.alive_urls())
        if self.ngrok.public_url and is_tunnel_url(self.ngrok.public_url):
            if self.ngrok.public_url not in urls:
                urls.append(self.ngrok.public_url)
        ShareRuntime.set_ws_urls([
            "wss://" + u.replace("https://", "").replace("http://", "") + "/ws" for u in urls
        ])
        if public_base and is_tunnel_url(public_base):
            ShareRuntime.receiver_link = public_base.rstrip("/") + "/receiver.html"
            ShareRuntime.last_error = ""
        else:
            ShareRuntime.receiver_link = ""
            ShareRuntime.last_error = (self.pool.last_error() if self.pool else "") or "tunnel_failed"
        link = ShareRuntime.receiver_link or "..."
        self.after(0, lambda: self.link_var.set(link))

    def _run_server(self):
        asyncio.set_event_loop(self.loop)
        from server import ServerRunner
        runner = ServerRunner(HTTP_PORT)
        self.loop.run_until_complete(runner.start())
        try:
            self.loop.run_forever()
        finally:
            self.loop.run_until_complete(runner.stop())

    def _tick(self):
        try:
            if self.pool:
                self.pool.refresh(HTTP_PORT)
                public = self.pool.primary_url() or self.ngrok.public_url
                self._publish(public)
                ShareRuntime.set_rows(self.pool.snapshot())
            rows = ShareRuntime.tunnel_rows()
            lines = []
            for r in rows:
                mark = "*" if r.get("primary") else " "
                rtt = r.get("rtt_ms", -1)
                rtt_s = f"{rtt}ms" if rtt and rtt > 0 else "-"
                lines.append(f"{mark} {r.get('name')} {r.get('state')} {rtt_s}")
            if self.ngrok.public_url:
                lines.append(f"  ngrok up")
            self.mon_tunnels.configure(text="\n".join(lines) or self.t("tunnels_none"))
            self.mon_summary.configure(
                text=f"{ShareRuntime.phase or '-'} | {ShareRuntime.speed_text()} | RTT {ShareRuntime.rtt_ms}ms | "
                     f"ack {ShareRuntime.acks_ok}/{ShareRuntime.acks_retry} | ws {ShareRuntime.clients}"
            )
            self.mon_logs.configure(state="normal")
            self.mon_logs.delete("1.0", "end")
            self.mon_logs.insert("1.0", "\n".join(list(ShareRuntime.logs)[:12]))
            self.mon_logs.configure(state="disabled")
        except Exception:
            pass
        self.after(1000, self._tick)

    def copy_link(self):
        link = self.link_var.get().strip()
        if not link or link == "..." or "127.0.0.1" in link:
            messagebox.showinfo(self.t("app_title"), self.t("no_link"))
            return
        self.clipboard_clear()
        self.clipboard_append(link)
        self.update()

    def share_link(self):
        link = self.link_var.get().strip()
        if not link or link == "..." or "127.0.0.1" in link:
            messagebox.showinfo(self.t("app_title"), self.t("no_link"))
            return
        system = platform.system()
        try:
            if system == "Windows":
                subprocess.run(["cmd", "/c", "start", "", link], check=False)
            elif system == "Darwin":
                subprocess.run(["open", link], check=False)
            else:
                subprocess.run(["xdg-open", link], check=False)
        except Exception:
            webbrowser.open(link)

    def choose_file(self):
        path = filedialog.askopenfilename()
        if not path:
            return
        self.file_path = path
        self.file_var.set(Path(path).name)
        self.send_file()

    def send_file(self):
        target = normalize_id(self.id_var.get().strip())
        if not target:
            messagebox.showinfo(self.t("app_title"), self.t("no_id"))
            return
        if not self.file_path:
            messagebox.showinfo(self.t("app_title"), self.t("no_file"))
            return
        if not ShareRuntime.receiver_link:
            messagebox.showwarning(self.t("app_title"), self.t("ngrok_needed"))
        if self.sending:
            return
        self.sending = True
        relay = ShareRuntime.local_relay()
        threading.Thread(target=self._send_worker, args=(self.file_path, target, relay), daemon=True).start()

    def _send_worker(self, path, target, relay):
        from sender_lib import Sender

        def status(code):
            mapping = {
                "waiting": self.t("status_waiting"),
                "accepted": self.t("status_sending"),
                "sending": self.t("status_sending"),
                "resume": self.t("status_sending"),
                "done": self.t("status_done"),
                "rejected": self.t("status_rejected"),
                "connected": self.t("status_waiting"),
            }
            self.after(0, lambda: self._set_status(mapping.get(code, code)))
            if code == "done":
                self.after(0, lambda: messagebox.showinfo(self.t("app_title"), self.t("status_done")))
                self.sending = False
            if code == "rejected":
                self.sending = False

        def progress(pct, text):
            self.after(0, lambda: self.progress.configure(value=pct))
            self.after(0, lambda: self._set_status(self.t("status_sending") + " " + text))

        try:
            sender = Sender(path, target, relay, on_status=status, on_progress=progress)
            asyncio.run(sender.run())
        except Exception as exc:
            self.after(0, lambda: self._set_status(str(exc)))
        finally:
            self.sending = False

    def reset_flow(self):
        self.id_var.set("")
        self.file_path = ""
        self.file_var.set("")
        self.progress.configure(value=0)
        self.sending = False
        self._set_status(self.t("status_ready"))

    def open_settings(self):
        win = tk.Toplevel(self)
        win.title(self.t("settings"))
        win.configure(bg=self.colors()["bg"])
        win.geometry("420x360")
        tk.Label(win, text=self.t("language"), bg=self.colors()["bg"], fg=self.colors()["text"]).pack(pady=(16, 4))
        lang = tk.StringVar(value=self.lang)
        ttk.Combobox(win, textvariable=lang, values=["ar", "en"], state="readonly").pack()
        tk.Label(win, text=self.t("theme"), bg=self.colors()["bg"], fg=self.colors()["text"]).pack(pady=(16, 4))
        theme = tk.StringVar(value=self.theme_name)
        ttk.Combobox(win, textvariable=theme, values=["dark", "blue", "green"], state="readonly").pack()
        tk.Label(win, text=self.t("ngrok_token"), bg=self.colors()["bg"], fg=self.colors()["text"]).pack(pady=(16, 4))
        token = tk.StringVar(value=self.cfg.get("ngrok_token", ""))
        tk.Entry(win, textvariable=token, show="*", width=42).pack()

        def save():
            self.cfg["language"] = lang.get()
            self.cfg["theme"] = theme.get()
            self.cfg["ngrok_token"] = token.get().strip()
            save_config(self.cfg)
            configure_ngrok(self.cfg["ngrok_token"])
            win.destroy()
            messagebox.showinfo(self.t("settings"), self.t("save"))
            os.execv(sys.executable, [sys.executable, str(ROOT / "app.py")])

        tk.Button(win, text=self.t("save"), command=save, bg=self.colors()["accent"],
                  fg="white", relief="flat", padx=20, pady=8).pack(pady=20)

    def _set_status(self, text):
        self.status_var.set(text)

    def on_close(self):
        try:
            self.ngrok.stop()
        except Exception:
            pass
        try:
            if self.pool:
                self.pool.stop()
        except Exception:
            pass
        if self.loop:
            self.loop.call_soon_threadsafe(self.loop.stop)
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
