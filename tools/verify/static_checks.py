#!/usr/bin/env python3
"""Static policy checks run before every APK build. Fails the build on any violation.

1. Network gate: no code may open network connections except the consent-gated transport.
2. Process execution: only the terminal backend and the explicit executable probe may start processes.
3. WebView hardening: no JavaScript bridges, no file access, preview loads only the virtual origin.
4. Manifest: only the launcher activity is exported; backups off; cleartext only to loopback.
5. Secrets: no likely credentials in source, docs or build files (test fixtures excluded).
6. Dependencies: the app declares no third-party runtime libraries.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
app = root / "app/src/main/kotlin"
engine = root / "core/engine/src/main/kotlin"
errors = []


def src_files(*bases):
    for b in bases:
        yield from (p for p in b.rglob("*.kt"))


def rel(p):
    return str(p.relative_to(root))


NETWORK = re.compile(r"\b(openConnection|HttpURLConnection|HttpsURLConnection|Socket\(|DatagramSocket|SSLSocket|okhttp|Retrofit|DownloadManager|InetAddress\.getByName)\b")
NETWORK_ALLOWED = {"core/engine/src/main/kotlin/dev/pocketforge/engine/consent/GatedTransport.kt"}
for f in src_files(app, engine):
    for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if NETWORK.search(line) and rel(f) not in NETWORK_ALLOWED and not line.strip().startswith(("*", "//")):
            errors.append(f"{rel(f)}:{n}: direct network API outside the consent-gated transport: {line.strip()}")
        if "UrlConnectionTransport(" in line and not rel(f).endswith(("AppContainer.kt", "GatedTransport.kt")):
            errors.append(f"{rel(f)}:{n}: raw transport constructed outside AppContainer")

PROC = re.compile(r"\b(ProcessBuilder|Runtime\.getRuntime\(\)\.exec)\b")
PROC_ALLOWED = {"core/engine/src/main/kotlin/dev/pocketforge/engine/terminal/Shell.kt",
                "app/src/main/kotlin/dev/pocketforge/ide/platform/DeviceProbe.kt"}
for f in src_files(app, engine):
    for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if PROC.search(line) and rel(f) not in PROC_ALLOWED and not line.strip().startswith(("*", "//")):
            errors.append(f"{rel(f)}:{n}: process execution outside the terminal backend: {line.strip()}")

for f in src_files(app):
    t = f.read_text(encoding="utf-8")
    for bad in ["addJavascriptInterface", "setAllowFileAccess(true)", "allowFileAccess = true", "allowUniversalAccessFromFileURLs = true",
                "MODE_WORLD_READABLE", "MODE_WORLD_WRITEABLE", "setWebContentsDebuggingEnabled(true)"]:
        if bad in t:
            errors.append(f"{rel(f)}: forbidden API {bad}")
    for m in re.finditer(r"loadUrl\(([^)]*)\)", t):
        if "PREVIEW_ORIGIN" not in m.group(1):
            errors.append(f"{rel(f)}: WebView.loadUrl must only load the virtual preview origin, got {m.group(1)}")

manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for m in re.finditer(r"<(activity|service|receiver|provider)\b[^>]*>", manifest, re.S):
    tag = m.group(0)
    name = re.search(r'android:name="([^"]+)"', tag).group(1)
    exported = re.search(r'android:exported="(true|false)"', tag)
    if exported is None:
        errors.append(f"manifest: {name} must declare android:exported explicitly")
    elif exported.group(1) == "true" and name != ".screens.HomeActivity":
        errors.append(f"manifest: {name} is exported; only the launcher activity may be")
if 'android:allowBackup="false"' not in manifest:
    errors.append("manifest: allowBackup must be false (projects and secrets never go to cloud backup)")
nsc = (root / "app/src/main/res/xml/network_security_config.xml").read_text(encoding="utf-8")
if '<base-config cleartextTrafficPermitted="false">' not in nsc or 'src="user"' in nsc:
    errors.append("network_security_config: base config must forbid cleartext and must not trust user CAs")
for d in re.findall(r"<domain[^>]*>([^<]+)</domain>", nsc):
    if d.strip() not in ("127.0.0.1", "localhost"):
        errors.append(f"network_security_config: cleartext allowed for non-loopback domain {d}")

SECRET = re.compile(r"(sk-ant-[A-Za-z0-9_\-]{20,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36,}|AIza[0-9A-Za-z_\-]{35}|-----BEGIN [A-Z ]*PRIVATE KEY-----)")
skip_dirs = {".git", "build-local", "build", "test", "local-verify"}
for p in root.rglob("*"):
    if not p.is_file() or any(part in skip_dirs for part in p.relative_to(root).parts):
        continue
    if p.suffix.lower() in {".jar", ".apk", ".png", ".webp", ".zip", ".p12", ".jks", ".class", ".dex"}:
        continue
    if p.name in {"SecretRedactor.kt", "pocketforge_build_host.py", "static_checks.py", "SelfTest.kt"}:
        continue  # contain detection patterns or labelled fake fixtures by design
    try:
        t = p.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    for n, line in enumerate(t.splitlines(), 1):
        if SECRET.search(line):
            errors.append(f"{rel(p)}:{n}: looks like a real credential")

gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
deps = re.findall(r'^\s*implementation\((.*)\)', gradle, re.M)
if any("project(" not in d for d in deps):
    errors.append(f"app/build.gradle.kts: third-party runtime dependencies are not allowed: {deps}")

# 7. Every source file must be tracked by git (a stray .gitignore rule once hid the engine's build/ package).
import subprocess
if (root / ".git").exists():
    try:
        ignored = subprocess.run(["git", "-C", str(root), "ls-files", "--others", "--ignored", "--exclude-standard"],
                                 capture_output=True, text=True, timeout=60).stdout.split("\n")
        for f in ignored:
            if f.endswith((".kt", ".java", ".xml", ".py", ".sh", ".md", ".kts", ".pro", ".toml")) and not f.startswith(("build-local/", "build/")) and "/build/" not in f.replace("engine/build/", ""):
                errors.append(f"{f}: source file is ignored by .gitignore and would be missing from the repository")
    except (OSError, subprocess.SubprocessError):
        pass

if errors:
    print("STATIC CHECKS FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("static checks: OK (network gate, process exec, WebView, manifest, secrets, dependencies)")
