"""Navigate the WebView to a route over CDP and save a device screenshot.

usage: python shot.py <ws-url> <adb-serial> <route> <out.png> [wait-seconds]
"""

import asyncio
import json
import os
import subprocess
import sys

import websockets

ADB = os.path.join(os.path.dirname(__file__), "..", "toolchain", "android-sdk", "platform-tools", "adb.exe")


async def navigate(url, route, wait):
    async with websockets.connect(url, max_size=None) as ws:
        await ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate",
                                  "params": {"expression": f"location.href = location.origin + {json.dumps(route)}"}}))
        await ws.recv()
    await asyncio.sleep(wait)


if __name__ == "__main__":
    ws_url, serial, route, out = sys.argv[1:5]
    wait = float(sys.argv[5]) if len(sys.argv) > 5 else 5
    if route != "-":
        asyncio.run(navigate(ws_url, route, wait))
    png = subprocess.run([ADB, "-s", serial, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    with open(out, "wb") as fh:
        fh.write(png)
    print(out, len(png))
