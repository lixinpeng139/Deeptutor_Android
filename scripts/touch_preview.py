"""Inject the working touch.css (+ the composer module's touch block) into the
WebView with touch emulation on, and screenshot one route.

usage: python touch_preview.py <ws-url> <adb-serial> <route> <out.png> [width height]
"""

import asyncio
import json
from pathlib import Path
import re
import subprocess
import sys

import websockets

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from touch_emulate import ADB, Session  # noqa: E402

WEB = HERE.parent / "build" / "src" / "web"


def live_css() -> str:
    css = (WEB / "app" / "touch.css").read_text(encoding="utf-8")
    module = (WEB / "components" / "chat" / "home" / "ChatComposer.module.css").read_text(encoding="utf-8")
    block = module[module.index("@media (hover: none) and (pointer: coarse)"):]
    block = block[: block.index("\n}\n") + 3]
    return css + "\n" + re.sub(r"\.actions\b", '[class*="ChatComposer_actions"]', block)


async def main(url, serial, route, out, width=None, height=None):
    async with websockets.connect(url, max_size=None) as ws:
        s = Session(ws)
        await s.touch()
        if width:
            await s.call("Emulation.setDeviceMetricsOverride", {"width": int(width), "height": int(height), "deviceScaleFactor": 1, "mobile": True})
        await s.go(route, 6)
        await s.eval("(c=>{let t=document.getElementById('dt-touch-live')||document.head.appendChild("
                     "Object.assign(document.createElement('style'),{id:'dt-touch-live'}));t.textContent=c})(%s)"
                     % json.dumps(live_css()))
        await asyncio.sleep(1)
        png = subprocess.run([str(ADB), "-s", serial, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
        Path(out).write_bytes(png)
        if width:
            await s.call("Emulation.clearDeviceMetricsOverride")
        print(out)


if __name__ == "__main__":
    asyncio.run(main(*sys.argv[1:7]))
