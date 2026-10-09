#!/usr/bin/env python3
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TOOLS = ROOT / "tools"
CONFIG = Path.home() / ".p2p-secure-share" / "config.json"
REQ = ROOT / "requirements.txt"
NGROK_LINUX = "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-amd64.tgz"
NGROK_WIN = "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-windows-amd64.zip"
NGROK_MAC = "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-darwin-amd64.zip"


def load_config():
    if CONFIG.exists():
        return json.loads(CONFIG.read_text(encoding="utf-8"))
    return {"setup_done": False, "language": "ar", "theme": "dark", "ngrok_token": ""}


def save_config(data):
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def pip_install():
    cmd = [sys.executable, "-m", "pip", "install", "-r", str(REQ)]
    subprocess.check_call(cmd)


def ngrok_path():
    name = "ngrok.exe" if platform.system() == "Windows" else "ngrok"
    local = TOOLS / name
    if local.exists():
        return local
    found = shutil.which("ngrok")
    return Path(found) if found else None


def install_ngrok():
    TOOLS.mkdir(parents=True, exist_ok=True)
    system = platform.system()
    if system == "Windows":
        url, archive = NGROK_WIN, TOOLS / "ngrok.zip"
    elif system == "Darwin":
        url, archive = NGROK_MAC, TOOLS / "ngrok.zip"
    else:
        url, archive = NGROK_LINUX, TOOLS / "ngrok.tgz"
    urllib.request.urlretrieve(url, archive)
    if archive.suffix == ".zip":
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(TOOLS)
    else:
        with tarfile.open(archive, "r:gz") as tf:
            tf.extractall(TOOLS)
    binary = ngrok_path()
    if binary and os.name != "nt":
        os.chmod(binary, 0o755)
    return binary


def configure_ngrok(token: str):
    binary = ngrok_path()
    if not binary or not token:
        return
    subprocess.run([str(binary), "config", "add-authtoken", token], check=False)


def ensure_ready(install_ngrok_bin=True):
    pip_install()
    if install_ngrok_bin and not ngrok_path():
        install_ngrok()
    cfg = load_config()
    cfg["setup_done"] = True
    save_config(cfg)
    if cfg.get("ngrok_token"):
        configure_ngrok(cfg["ngrok_token"])
