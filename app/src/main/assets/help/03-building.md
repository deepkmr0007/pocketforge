# Building APKs
Android does not let normal apps run downloaded compilers from their own storage (the "W^X" policy for apps targeting Android 10+), and Gradle with the Android plugin needs far more than a phone app can bundle. PocketForge is honest about this and offers three real routes:

## 1. On this phone, through Termux (experimental)
For **android-lite** projects (plain Android framework, no libraries). You install Termux yourself (F-Droid or GitHub build) and its packages:
```
pkg install aapt2 d8 apksigner openjdk-17 zip
termux-setup-storage
mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties
```
You also need an `android.jar` (Android SDK platform, API 35) at `~/.pocketforge/android.jar` or in `ANDROID_JAR`.

In Build center: **Choose folder** (e.g. Internal storage › PocketForge), confirm the path as Termux sees it, then **Build debug APK on this phone**. PocketForge shows the exact script first; it runs aapt2 → javac (or kotlinc) → d8 → apksigner and never downloads or installs anything. The APK is copied back, re-hashed and inspected before the build is marked successful. Builds are signed with a local **debug** key created in Termux (`~/.pocketforge/debug.keystore`) — not for publishing.

## 2. On your own Build Host
Run `tools/build-host/pocketforge_build_host.py` (Python 3, no extra packages) on a computer with JDK 17+, Android SDK and Gradle. It prints a URL, a certificate fingerprint and a pairing token; enter them in Build center. Each build uploads a ZIP of the files you select, **after you approve that upload**. The connection is pinned to the host's certificate. Build scripts run on that computer with that user's permissions — only build projects you trust, ideally in a VM or container.

## 3. On a computer
**Export ZIP** produces a clean archive (no secrets, no build outputs) with `BUILD_INSTRUCTIONS.md` for Android Studio or Gradle.

## Installing
**Inspect / install** shows package, version, SDK levels, debug/release, signature schemes and whether an installed app uses the same signing key. Installing always goes through Android's own installer and confirmation; you must allow "Install unknown apps" for PocketForge once.

No emulator: running an Android emulator needs hardware virtualization that phones do not expose to apps. Test on this phone or a second device.
