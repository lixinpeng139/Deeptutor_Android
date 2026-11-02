"""Wait for the app on a device to finish (re)unpacking and start, then save the
DevTools websocket URL for the other scripts.

usage: python wait_ready.py <adb-serial> <payload-version> <out-ws-file> [local-port]
"""

import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

ADB = str(Path(__file__).parent.parent / "toolchain" / "android-sdk" / "platform-tools" / "adb.exe")
PKG = "com.opendeeptutor.android"


def adb(serial, *args):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def main(serial, version, out, port="29222"):
    start = time.time()
    while time.time() - start < 900:
        installed = adb(serial, "exec-out", "run-as", PKG, "cat", "files/rootfs/.payload-version").strip()
        log = adb(serial, "exec-out", "run-as", PKG, "cat", "files/logs/runtime.log")
        if installed == version and "Uvicorn running" in log and "Ready in" in log:
            break
        time.sleep(3)
    else:
        raise SystemExit("timed out waiting for the runtime")
    print(f"runtime up after {time.time() - start:.0f}s")
    for _ in range(60):
        sockets = [line.split("@")[-1] for line in adb(serial, "shell", "cat", "/proc/net/unix").splitlines()
                   if "webview_devtools_remote" in line]
        if sockets:
            adb(serial, "forward", f"tcp:{port}", f"localabstract:{sockets[0].strip()}")
            try:
                pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=3))
                page = next(p for p in pages if p["type"] == "page" and p["url"].startswith("http://127.0.0.1"))
                Path(out).write_text(page["webSocketDebuggerUrl"], encoding="utf-8")
                print(page["url"])
                return
            except (StopIteration, OSError, ValueError):
                pass
        time.sleep(2)
    raise SystemExit("no DevTools page")


if __name__ == "__main__":
    main(*sys.argv[1:])
