"""Navigate the app's WebView through several routes and run touch_audit.js on each.

usage: python touch_survey.py <ws-debugger-url> [route ...]
"""

import asyncio
import json
from pathlib import Path
import sys

import websockets

AUDIT = (Path(__file__).parent / "touch_audit.js").read_text(encoding="utf-8")
ROUTES = ["/chat", "/learning", "/knowledge-bases", "/notebooks", "/calendar", "/kanban",
          "/memory", "/space", "/co-writer", "/partners", "/settings/models", "/learning/books"]


async def main(url, routes):
    async with websockets.connect(url, max_size=None) as ws:
        n = 0

        async def call(method, params=None):
            nonlocal n
            n += 1
            await ws.send(json.dumps({"id": n, "method": method, "params": params or {}}))
            while True:
                msg = json.loads(await ws.recv())
                if msg.get("id") == n:
                    return msg.get("result", {})

        origin = (await call("Runtime.evaluate", {"expression": "location.origin", "returnByValue": True}))["result"]["value"]
        for route in routes:
            await call("Page.navigate", {"url": origin + route})
            await asyncio.sleep(5)
            r = await call("Runtime.evaluate", {"expression": AUDIT, "returnByValue": True})
            d = json.loads(r["result"]["value"])
            print(f"== {route}: total={d['total']} small<40={d['small']} tiny<28={d['tiny']} hidden={d['hidden']}")
            for s in d["samples"][:12]:
                print("   ", s)
            if d["hiddenSamples"]:
                print("    hidden:", d["hiddenSamples"][:8])
        await call("Page.navigate", {"url": origin + "/chat"})


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2:] or ROUTES))
