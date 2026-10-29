"""PC Control Windows agent: authenticated commands, no third-party Python packages.
Start via start.bat or python agent.py. Requires Windows 10/11 and Python 3.10+.
"""
from __future__ import annotations

import ctypes
import csv
import io
import re
import hashlib
import hmac
import json
import os
import platform
import secrets
import socket
import subprocess
import threading
import time
import tkinter as tk
from ctypes import wintypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tkinter import messagebox, ttk

PORT = 43821
VERSION = "1.1"
APP_DIR = Path(os.getenv("APPDATA", str(Path.home()))) / "PCControl"
CONFIG = APP_DIR / "settings.json"
MAX_BODY = 4096
used_nonces: dict[str, float] = {}
nonce_lock = threading.Lock()


def load_secret() -> str:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    if CONFIG.exists():
        try:
            secret = json.loads(CONFIG.read_text(encoding="utf-8"))["secret"]
            if len(bytes.fromhex(secret)) == 32:
                return secret.lower()
        except (ValueError, KeyError, OSError):
            pass
    secret = secrets.token_hex(32)
    save_secret(secret)
    return secret


def save_secret(secret: str) -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG.with_suffix(".tmp")
    tmp.write_text(json.dumps({"secret": secret}), encoding="utf-8")
    tmp.replace(CONFIG)


class State:
    secret: str = ""
    allow_power = True


def local_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 80))  # no packets sent
            return s.getsockname()[0]
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"


def available_macs() -> list[tuple[str, str]]:
    """List candidate Windows network adapters without requiring admin rights.

    WOL normally requires a wired adapter; listing a MAC does not imply WOL support.
    """
    if os.name != "nt":
        return []
    try:
        result = subprocess.run(["getmac", "/v", "/fo", "csv"], capture_output=True,
                                timeout=5, creationflags=subprocess.CREATE_NO_WINDOW)
        raw = result.stdout.decode("mbcs", errors="replace")
        rows = list(csv.reader(io.StringIO(raw)))
        found = []
        for row in rows[1:]:
            if len(row) < 3:
                continue
            name = row[1].strip() or row[0].strip()
            mac = row[2].strip().replace("-", ":").upper()
            if re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", mac):
                found.append((name[:55], mac))
        return found
    except (OSError, UnicodeError, subprocess.TimeoutExpired, csv.Error):
        return []


def verify(method: str, path: str, body: bytes, headers: dict[str, str], now: float | None = None) -> bool:
    now = time.time() if now is None else now
    headers = {k.lower(): v for k, v in headers.items()}
    t = headers.get("x-pc-time", "")
    nonce = headers.get("x-pc-nonce", "")
    mac = headers.get("x-pc-signature", "")
    if not (t.isdecimal() and len(t) in (10, 13) and 16 <= len(nonce) <= 64 and len(mac) == 64):
        return False
    timestamp = int(t) / (1000 if len(t) == 13 else 1)
    if abs(now - timestamp) > 120:
        return False
    payload_hash = hashlib.sha256(body).hexdigest()
    to_sign = f"{method}\n{path}\n{t}\n{nonce}\n{payload_hash}".encode("utf-8")
    expected = hmac.new(bytes.fromhex(State.secret), to_sign, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(mac.lower(), expected):
        return False
    with nonce_lock:
        for n, expiry in list(used_nonces.items()):
            if expiry < now:
                del used_nonces[n]
        if nonce in used_nonces:
            return False
        used_nonces[nonce] = now + 150
    return True


def battery_status() -> dict:
    class SYSTEM_POWER_STATUS(ctypes.Structure):
        _fields_ = [("ACLineStatus", ctypes.c_byte), ("BatteryFlag", ctypes.c_byte),
                    ("BatteryLifePercent", ctypes.c_byte), ("SystemStatusFlag", ctypes.c_byte),
                    ("BatteryLifeTime", wintypes.DWORD), ("BatteryFullLifeTime", wintypes.DWORD)]
    s = SYSTEM_POWER_STATUS()
    if os.name == "nt" and ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)):
        return {"percent": s.BatteryLifePercent if 0 <= s.BatteryLifePercent <= 100 else None,
                "charging": s.ACLineStatus == 1}
    return {"percent": None, "charging": None}


def memory_status() -> dict:
    class MEMORYSTATUSEX(ctypes.Structure):
        _fields_ = [("dwLength", wintypes.DWORD), ("dwMemoryLoad", wintypes.DWORD),
                    ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
    m = MEMORYSTATUSEX()
    m.dwLength = ctypes.sizeof(m)
    if os.name == "nt" and ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m)):
        return {"used_percent": int(m.dwMemoryLoad), "total_gb": round(m.ullTotalPhys / 1073741824, 1)}
    return {"used_percent": None, "total_gb": None}


def info() -> dict:
    uptime = None
    if os.name == "nt":
        kernel = ctypes.windll.kernel32
        kernel.GetTickCount64.restype = ctypes.c_ulonglong
        uptime = int(kernel.GetTickCount64() // 1000)
    return {"name": platform.node(), "os": platform.platform(), "battery": battery_status(),
            "memory": memory_status(), "uptime_seconds": uptime,
            "version": VERSION, "power_enabled": State.allow_power}


def media_key(code: int) -> None:
    if os.name != "nt":
        raise OSError("Windows only")
    # Windows virtual keyboard media keys: mute 0xAD, volume down 0xAE, up 0xAF
    ctypes.windll.user32.keybd_event(code, 0, 0, 0)
    ctypes.windll.user32.keybd_event(code, 0, 2, 0)


def execute(action: str) -> str:
    if action == "volume_up":
        media_key(0xAF)
        return "Громкость увеличена"
    if action == "volume_down":
        media_key(0xAE)
        return "Громкость уменьшена"
    if action == "mute":
        media_key(0xAD)
        return "Звук переключён"
    if action in ("lock", "sleep", "shutdown", "restart", "cancel_shutdown"):
        if not State.allow_power:
            raise PermissionError("Power controls disabled by user")
        if os.name != "nt":
            raise OSError("Windows only")
        if action == "cancel_shutdown":
            subprocess.run(["shutdown", "/a"], capture_output=True, timeout=8)
            return "Отмена выключения отправлена"
        if action == "lock":
            if not ctypes.windll.user32.LockWorkStation():
                raise OSError("Unable to lock workstation")
            return "Компьютер заблокирован"
        if action == "sleep":
            # Delay to deliver the response before Windows enters sleep.
            threading.Timer(1.5, lambda: ctypes.windll.powrprof.SetSuspendState(False, False, False)).start()
            return "Переход в сон"
        if action in ("shutdown", "restart"):
            flag = "/s" if action == "shutdown" else "/r"
            subprocess.Popen(["shutdown", flag, "/t", "30"], creationflags=subprocess.CREATE_NO_WINDOW)
            return "Выполнится через 30 секунд; можно отменить"
    raise ValueError("Unsupported command")


class Handler(BaseHTTPRequestHandler):
    server_version = "PCControl/1"
    def log_message(self, fmt: str, *args: object) -> None:
        pass  # do not log authentication tokens or requests to files

    def respond(self, code: int, obj: dict) -> None:
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(b)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def handle_request(self) -> None:
        if self.path not in ("/v1/status", "/v1/action") or self.command not in ("GET", "POST"):
            return self.respond(404, {"ok": False, "error": "Not found"})
        if (self.command, self.path) not in (("GET", "/v1/status"), ("POST", "/v1/action")):
            return self.respond(405, {"ok": False, "error": "Method not allowed"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.respond(400, {"ok": False, "error": "Bad content length"})
        if length < 0 or length > MAX_BODY:
            return self.respond(413, {"ok": False, "error": "Payload too large"})
        body = self.rfile.read(length)
        if not verify(self.command, self.path, body, dict(self.headers)):
            return self.respond(401, {"ok": False, "error": "Authorization failed"})
        if self.path == "/v1/status":
            return self.respond(200, {"ok": True, "device": info()})
        try:
            data = json.loads(body.decode("utf-8"))
            if not isinstance(data, dict) or not isinstance(data.get("action"), str):
                raise ValueError("Invalid action")
            result = execute(data["action"])
            return self.respond(200, {"ok": True, "message": result})
        except PermissionError as e:
            return self.respond(403, {"ok": False, "error": str(e)})
        except (ValueError, UnicodeError, OSError) as e:
            return self.respond(400, {"ok": False, "error": str(e)[:120]})

    def do_GET(self) -> None:
        self.handle_request()

    def do_POST(self) -> None:
        self.handle_request()


class App:
    def __init__(self) -> None:
        State.secret = load_secret()
        self.root = tk.Tk()
        self.root.title("PC Control • Windows")
        self.root.geometry("625x690")
        self.root.minsize(570, 630)
        self.root.configure(bg="#0b1220")
        self.server: ThreadingHTTPServer | None = None
        self.thread: threading.Thread | None = None
        self.status_text = tk.StringVar(value="●  Не запущен")
        self.key_text = tk.StringVar(value="••••••••••••••••")
        self.key_visible = False
        self.macs = available_macs()
        self.mac_choice = tk.StringVar(value=(
            f"{self.macs[0][1]}  ·  {self.macs[0][0]}" if self.macs else "MAC не найден · см. ipconfig /all"))
        self.build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.close)

    def label(self, parent, txt, size=12, color="#b3bfd8", bold=False) -> tk.Label:
        return tk.Label(parent, text=txt, fg=color, bg=parent.cget("bg"), font=("Segoe UI", size, "bold" if bold else "normal"), anchor="w")

    def build_ui(self) -> None:
        root = self.root
        box = tk.Frame(root, bg="#0b1220", padx=28, pady=20)
        box.pack(fill="both", expand=True)
        self.label(box, "PC CONTROL", 28, "#f2f5ff", True).pack(anchor="w")
        self.label(box, "Управление ноутбуком с Android • локальная сеть", 11).pack(anchor="w", pady=(2, 20))
        panel = tk.Frame(box, bg="#172238", padx=18, pady=18)
        panel.pack(fill="x")
        tk.Label(panel, textvariable=self.status_text, fg="#72edc9", bg="#172238", font=("Segoe UI", 13, "bold")).pack(anchor="w")
        self.label(panel, f"Адрес для телефона: {local_ip()}:{PORT}", 15, "#f1f5ff", True).pack(anchor="w", pady=(12, 6))
        self.label(panel, f"Устройство: {platform.node()}", 11).pack(anchor="w")
        actions = tk.Frame(box, bg="#0b1220")
        actions.pack(fill="x", pady=15)
        tk.Button(actions, text="▶ Запустить", command=self.start, bg="#7668ef", fg="white", relief="flat", font=("Segoe UI", 12, "bold"), padx=14, pady=9).pack(side="left")
        tk.Button(actions, text="■ Остановить", command=self.stop, bg="#35415d", fg="white", relief="flat", font=("Segoe UI", 12), padx=14, pady=9).pack(side="left", padx=10)
        pair = tk.Frame(box, bg="#172238", padx=18, pady=14)
        pair.pack(fill="x")
        self.label(pair, "КЛЮЧ ПОДКЛЮЧЕНИЯ", 11, "#9dc4ff", True).pack(anchor="w")
        tk.Entry(pair, textvariable=self.key_text, show="", readonlybackground="#172238", fg="#f1f5ff", relief="flat", font=("Consolas", 11), state="readonly").pack(fill="x", pady=8)
        tools = tk.Frame(pair, bg="#172238")
        tools.pack(anchor="w")
        tk.Button(tools, text="Показать / скрыть", command=self.show_secret).pack(side="left")
        tk.Button(tools, text="Скопировать", command=self.copy_secret).pack(side="left", padx=8)
        tk.Button(tools, text="Сменить ключ", command=self.rotate_secret).pack(side="left")
        nic = tk.Frame(box, bg="#172238", padx=18, pady=12)
        nic.pack(fill="x", pady=(13, 3))
        self.label(nic, "WAKE-ON-LAN · MAC-АДРЕС", 11, "#9dc4ff", True).pack(anchor="w")
        self.mac_picker = ttk.Combobox(nic, state="readonly", textvariable=self.mac_choice,
                                      values=[f"{mac}  ·  {name}" for name, mac in self.macs]
                                      or ["MAC не найден · см. ipconfig /all"])
        self.mac_picker.pack(fill="x", pady=(8, 7))
        tk.Button(nic, text="Скопировать MAC", command=self.copy_mac).pack(anchor="w")
        self.label(nic, "Wake-on-LAN требует поддержки BIOS и адаптера. По Wi-Fi часто не работает.", 9, "#fbbf89").pack(anchor="w", pady=(8, 0))
        self.label(box, "1. Нажми «Запустить».  2. В Windows Firewall разреши доступ только в частной сети.", 10).pack(anchor="w", pady=(12, 4))
        self.label(box, "3. Телефон и ноутбук должны быть в одной Wi-Fi сети.", 10).pack(anchor="w", pady=4)
        self.label(box, "4. В Android введи IP и скопированный ключ, затем нажми «Подключиться».", 10).pack(anchor="w", pady=4)
        self.label(box, "Через интернет: только через личную VPN-сеть, без проброса порта.", 10, "#fbbf89").pack(anchor="w", pady=(12, 3))
        self.allow = tk.BooleanVar(value=True)
        ttk.Checkbutton(box, text="Разрешить блокировку, сон и питание", variable=self.allow,
                        command=lambda: setattr(State, "allow_power", self.allow.get())).pack(anchor="w", pady=5)

    def start(self) -> None:
        if self.server:
            return
        try:
            self.server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
        except OSError as e:
            messagebox.showerror("Невозможно запустить", str(e))
            return
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.status_text.set("●  Сервер запущен")

    def stop(self) -> None:
        if not self.server:
            return
        server = self.server
        self.server = None
        threading.Thread(target=lambda: (server.shutdown(), server.server_close()), daemon=True).start()
        self.status_text.set("●  Остановлен")

    def show_secret(self) -> None:
        self.key_visible = not self.key_visible
        self.key_text.set(State.secret if self.key_visible else "••••••••••••••••")

    def copy_secret(self) -> None:
        self.root.clipboard_clear()
        self.root.clipboard_append(State.secret)
        messagebox.showinfo("Скопировано", "Ключ скопирован. Не передавай его другим людям.")

    def copy_mac(self) -> None:
        value = self.mac_choice.get().split("  ·  ")[0].strip()
        if not re.fullmatch(r"(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", value):
            messagebox.showwarning("MAC", "Не найден корректный MAC. Проверь ipconfig /all.")
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(value)
        messagebox.showinfo("MAC скопирован", "Вставь MAC в раздел Wake-on-LAN на Android.\n"
                            "Для пробуждения обычно нужен Ethernet и разрешение в BIOS/Windows.")

    def rotate_secret(self) -> None:
        if not messagebox.askyesno("Сменить ключ?", "Android потеряет доступ до ввода нового ключа. Продолжить?"):
            return
        State.secret = secrets.token_hex(32)
        save_secret(State.secret)
        with nonce_lock:
            used_nonces.clear()
        self.key_text.set(State.secret if self.key_visible else "••••••••••••••••")

    def close(self) -> None:
        if self.server:
            self.server.server_close()
        self.root.destroy()


if __name__ == "__main__":
    if os.name != "nt":
        raise SystemExit("Windows 10 / Windows 11 required")
    App().root.mainloop()
