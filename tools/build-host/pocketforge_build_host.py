#!/usr/bin/env python3
"""
PocketForge Build Host — run on YOUR computer so your phone can build APKs.

* HTTPS only, with a self-signed certificate the phone pins by SHA-256 fingerprint.
* Pairing token (random, 256-bit) required on every request; compared in constant time.
* Accepts exactly one endpoint: POST /v1/build with a project ZIP and an allow-listed Gradle task.
* ZIP extraction is hardened (no traversal, no absolute paths, size/count/ratio limits, no symlinks).
* Uses the Gradle installed on this computer by default. A project's own ./gradlew (which downloads
  whatever distributionUrl it names) is only used with --allow-wrapper.
* One build at a time; temp folder deleted after every build; log tail redacted for common secrets.

SECURITY: building a project runs its build scripts with YOUR user's permissions on this computer.
Only build projects you trust, or run this host inside a VM/container or as a dedicated low-privilege user.

Python 3.9+ and the `openssl` command are required. No third-party packages.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import http.server
import io
import json
import os
import re
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from pathlib import Path, PurePosixPath

VERSION = "1.0.0"
ALLOWED_TASKS = {"assembleDebug", "testDebugUnitTest", "lintDebug", "assembleRelease"}
MAX_UPLOAD = 64 * 1024 * 1024
MAX_EXTRACT = 512 * 1024 * 1024
MAX_ENTRIES = 20_000
MAX_RATIO = 100
LOG_TAIL = 400 * 1024

SECRET_PATTERNS = [
    re.compile(r"sk-ant-[A-Za-z0-9_\-]{20,}"),
    re.compile(r"sk-(?:proj-)?[A-Za-z0-9_\-]{20,}"),
    re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),
    re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    re.compile(r"(?i)\b((?:password|passwd|secret|token|api[_\-]?key|storePassword|keyPassword)\s*[:=]\s*[\"']?)([^\"'\s,;]{4,})"),
]


def redact(text: str) -> str:
    for p in SECRET_PATTERNS:
        text = p.sub(lambda m: (m.group(1) if m.lastindex and m.lastindex >= 2 else "") + "<redacted>", text)
    return text


def config_dir() -> Path:
    d = Path(os.environ.get("PF_HOST_HOME", Path.home() / ".pocketforge-build-host"))
    d.mkdir(mode=0o700, parents=True, exist_ok=True)
    return d


def ensure_identity(d: Path, hostnames: list[str]) -> tuple[Path, Path, str, str]:
    cert, key, cfg = d / "cert.pem", d / "key.pem", d / "config.json"
    if not cert.exists() or not key.exists():
        if shutil.which("openssl") is None:
            sys.exit("openssl not found. Install OpenSSL and run again.")
        san = ",".join([f"DNS:{h}" for h in hostnames if not re.match(r"^\d+\.\d+\.\d+\.\d+$", h)] +
                       [f"IP:{h}" for h in hostnames if re.match(r"^\d+\.\d+\.\d+\.\d+$", h)])
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
             "-keyout", str(key), "-out", str(cert), "-days", "825", "-subj", "/CN=PocketForge Build Host",
             "-addext", f"subjectAltName={san}"],
            check=True, capture_output=True)
        os.chmod(key, 0o600)
    if cfg.exists():
        token = json.loads(cfg.read_text())["token"]
    else:
        token = secrets.token_urlsafe(32)
        cfg.write_text(json.dumps({"token": token}))
        os.chmod(cfg, 0o600)
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    fingerprint = hashlib.sha256(der).hexdigest()
    return cert, key, token, fingerprint


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the outbound interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def safe_extract(data: bytes, dest: Path) -> int:
    total = 0
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        infos = z.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError("too many entries")
        seen = set()
        for info in infos:
            name = info.filename.replace("\\", "/")
            p = PurePosixPath(name)
            if p.is_absolute() or ".." in p.parts or re.match(r"^[A-Za-z]:", name) or "\x00" in name:
                raise ValueError(f"unsafe path: {name}")
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"symlink not allowed: {name}")
            key = name.lower()
            if key in seen:
                raise ValueError(f"duplicate entry: {name}")
            seen.add(key)
            target = (dest / name).resolve()
            if not str(target).startswith(str(dest.resolve()) + os.sep) and target != dest.resolve():
                raise ValueError(f"escapes destination: {name}")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                while True:
                    chunk = src.read(65536)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > MAX_EXTRACT:
                        raise ValueError("archive expands too much")
                    if total > 8 * 1024 * 1024 and total > MAX_RATIO * len(data):
                        raise ValueError("suspicious compression ratio")
                    out.write(chunk)
    return total


def find_apk(root: Path, task: str) -> Path | None:
    flavor = "release" if "Release" in task else "debug"
    candidates = [p for p in root.glob("**/build/outputs/apk/**/*.apk") if flavor in str(p.parent)]
    if not candidates:
        candidates = list(root.glob("**/build/outputs/apk/**/*.apk"))
    return max(candidates, key=lambda p: p.stat().st_mtime) if candidates else None


class State:
    token = ""
    lock = threading.Lock()
    failures: dict[str, list[float]] = {}
    args: argparse.Namespace


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = f"PocketForgeBuildHost/{VERSION}"

    def log_message(self, fmt, *a):  # concise, no request bodies
        sys.stderr.write("[%s] %s %s\n" % (time.strftime("%H:%M:%S"), self.client_address[0], fmt % a))

    def send_json(self, code: int, obj: dict):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        ip = self.client_address[0]
        recent = [t for t in State.failures.get(ip, []) if time.time() - t < 300]
        State.failures[ip] = recent
        if len(recent) >= 10:
            return False
        got = self.headers.get("Authorization", "")
        ok = hmac.compare_digest(got.encode(), f"Bearer {State.token}".encode())
        if not ok:
            recent.append(time.time())
        return ok

    def do_GET(self):
        if self.path == "/v1/ping" and self.authorized():
            return self.send_json(200, {"ok": True, "version": VERSION})
        self.send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path != "/v1/build":
            return self.send_json(404, {"ok": False, "error": "not found"})
        if not self.authorized():
            return self.send_json(401, {"ok": False, "error": "bad or missing pairing token"})
        task = self.headers.get("X-PocketForge-Task", "")
        if task not in ALLOWED_TASKS:
            return self.send_json(400, {"ok": False, "error": f"task not allowed: {task}"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length <= 0 or length > MAX_UPLOAD:
            return self.send_json(413, {"ok": False, "error": "upload missing or too large"})
        data = self.rfile.read(length)
        if not State.lock.acquire(blocking=False):
            return self.send_json(429, {"ok": False, "error": "another build is running"})
        work = Path(tempfile.mkdtemp(prefix="pf-build-"))
        try:
            result = self.run_build(data, work, task)
            self.send_json(200, result)
        except Exception as e:  # report, never crash the host
            self.send_json(200, {"ok": False, "exitCode": None, "error": str(e), "log": ""})
        finally:
            State.lock.release()
            if not State.args.keep:
                shutil.rmtree(work, ignore_errors=True)

    def run_build(self, data: bytes, work: Path, task: str) -> dict:
        try:
            safe_extract(data, work)
        except (ValueError, zipfile.BadZipFile) as e:
            return {"ok": False, "exitCode": None, "error": f"rejected upload: {e}", "log": ""}
        if State.args.build_cmd:
            cmd = ["/bin/sh", "-c", State.args.build_cmd]
            tool = {"command": "custom"}
        elif State.args.allow_wrapper and (work / "gradlew").exists():
            os.chmod(work / "gradlew", 0o755)
            cmd = [str(work / "gradlew"), task, "--no-daemon", "--console=plain"]
            tool = {"gradle": "project wrapper"}
        else:
            gradle = shutil.which("gradle")
            if gradle is None:
                return {"ok": False, "exitCode": None, "error": "Gradle is not installed on the Build Host (or start it with --allow-wrapper).", "log": ""}
            cmd = [gradle, task, "--no-daemon", "--console=plain"]
            ver = subprocess.run([gradle, "--version"], capture_output=True, text=True, timeout=120).stdout
            m = re.search(r"Gradle (\S+)", ver)
            tool = {"gradle": m.group(1) if m else "unknown"}
        java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines() if shutil.which("java") else []
        if java:
            tool["java"] = java[0]
        env = {k: v for k, v in os.environ.items() if not re.search(r"(?i)token|secret|password|key", k)}
        start = time.time()
        try:
            p = subprocess.run(cmd, cwd=work, capture_output=True, text=True, timeout=State.args.timeout, env=env)
            log, code = (p.stdout + "\n" + p.stderr), p.returncode
        except subprocess.TimeoutExpired as e:
            return {"ok": False, "exitCode": None, "error": f"build timed out after {State.args.timeout}s",
                    "log": redact(((e.stdout or "") if isinstance(e.stdout, str) else "")[-LOG_TAIL:])}
        log = redact(log.replace(str(work), "<project>"))[-LOG_TAIL:]
        out = {"ok": code == 0, "exitCode": code, "durationMs": int((time.time() - start) * 1000), "log": log, "toolchain": tool}
        if code != 0:
            return out
        if task.startswith("assemble"):
            apk = find_apk(work, task)
            if apk is None:
                out.update(ok=False, error="build succeeded but no APK was found under build/outputs/apk")
                return out
            b = apk.read_bytes()
            out["artifact"] = {"name": apk.name, "sha256": hashlib.sha256(b).hexdigest(), "size": len(b),
                               "base64": base64.b64encode(b).decode()}
        return out


def main():
    ap = argparse.ArgumentParser(description="PocketForge Build Host")
    ap.add_argument("--host", default="0.0.0.0", help="interface to listen on (default all)")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--timeout", type=int, default=1200, help="max seconds per build")
    ap.add_argument("--allow-wrapper", action="store_true", help="use the project's ./gradlew (runs its distributionUrl)")
    ap.add_argument("--keep", action="store_true", help="keep build folders for debugging")
    ap.add_argument("--build-cmd", help=argparse.SUPPRESS)  # testing hook
    ap.add_argument("--print-pairing", action="store_true", help="print pairing info and exit")
    State.args = args = ap.parse_args()

    ip = lan_ip()
    cert, key, token, fp = ensure_identity(config_dir(), ["localhost", "127.0.0.1", ip])
    State.token = token
    print("PocketForge Build Host", VERSION)
    print(f"  URL:          https://{ip}:{args.port}")
    print(f"  Fingerprint:  {fp}")
    print(f"  Pair token:   {token}")
    print("Enter these in PocketForge → Build Center → Pair Build Host. Keep the token private.")
    if args.print_pairing:
        return
    httpd = http.server.ThreadingHTTPServer((args.host, args.port), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, key)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"Listening on {args.host}:{args.port} (Ctrl+C to stop)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
