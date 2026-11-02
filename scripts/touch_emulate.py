"""Emulate a phone/tablet touchscreen's media features in the WebView via CDP,
then optionally navigate and run the touch audit.

The Android emulator reports `pointer: fine` even with a multi-touch screen;
real devices report `hover: none` + `pointer: coarse`. Emulation lasts while
this script's DevTools session stays open, so it does the checks itself.

usage: python touch_emulate.py <ws-url> <command> [args]
  survey [route ...]        audit routes with touch media emulated
  shot <serial> <route> <out.png>   screenshot one route with touch emulated
  eval <js>                 evaluate with touch emulated
"""

import asyncio
import json
from pathlib import Path
import subprocess
import sys

import websockets

HERE = Path(__file__).parent
AUDIT = (HERE / "touch_audit.js").read_text(encoding="utf-8")
ADB = HERE.parent / "toolchain" / "android-sdk" / "platform-tools" / "adb.exe"
ROUTES = ["/chat", "/learning", "/knowledge-bases", "/notebooks", "/calendar", "/kanban",
          "/memory", "/space", "/co-writer", "/partners", "/settings/models", "/learning/books"]


class Session:
    def __init__(self, ws):
        self.ws = ws
        self.n = 0

    async def call(self, method, params=None):
        self.n += 1
        await self.ws.send(json.dumps({"id": self.n, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(await self.ws.recv())
            if msg.get("id") == self.n:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    async def eval(self, expression):
        r = await self.call("Runtime.evaluate", {"expression": expression, "returnByValue": True, "awaitPromise": True})
        return r.get("result", {}).get("value")

    async def touch(self):
        await self.call("Emulation.setEmulatedMedia", {"features": [
            {"name": "hover", "value": "none"}, {"name": "any-hover", "value": "none"},
            {"name": "pointer", "value": "coarse"}, {"name": "any-pointer", "value": "coarse"},
        ]})
        await self.call("Emulation.setTouchEmulationEnabled", {"enabled": True, "maxTouchPoints": 5})

    async def go(self, route, wait=5.0):
        if not getattr(self, "origin", None):
            for _ in range(20):
                origin = await self.eval("location.origin")
                if origin and origin.startswith("http"):
                    self.origin = origin
                    break
                await asyncio.sleep(0.5)
        await self.call("Page.navigate", {"url": self.origin + route})
        await asyncio.sleep(wait)


async def main(url, cmd, args):
    async with websockets.connect(url, max_size=None) as ws:
        s = Session(ws)
        await s.touch()
        if cmd == "survey":
            for route in args or ROUTES:
                await s.go(route)
                d = json.loads(await s.eval(AUDIT))
                print(f"== {route}: total={d['total']} small<40={d['small']} tiny<28={d['tiny']} hidden={d['hidden']}")
                for line in d["samples"][:10]:
                    print("   ", line)
                if d["hiddenSamples"]:
                    print("    hidden:", d["hiddenSamples"][:8])
        elif cmd == "shot":
            serial, route, out = args
            if route != "-":
                await s.go(route, 6)
            png = subprocess.run([str(ADB), "-s", serial, "exec-out", "screencap", "-p"],
                                 capture_output=True, check=True).stdout
            Path(out).write_bytes(png)
            print(out)
        elif cmd == "eval":
            print(await s.eval(args[0]))


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3:]))
