"""Tiny Chrome DevTools Protocol client for debugging the app's WebView over adb forward.

usage: python cdp.py <ws-debugger-url> <mode> [arg]
  eval <js>        evaluate an expression and print the result
  console <secs>   reload and print console messages / exceptions for <secs>
  wsframes <secs>  print WebSocket frames received by the page for <secs>
"""

import asyncio
import json
import sys

import websockets


async def main(url: str, mode: str, arg: str) -> None:
    async with websockets.connect(url, max_size=None) as ws:
        ids = iter(range(1, 10**9))

        async def call(method, params=None):
            i = next(ids)
            await ws.send(json.dumps({"id": i, "method": method, "params": params or {}}))
            while True:
                msg = json.loads(await ws.recv())
                if msg.get("id") == i:
                    return msg
                handle(msg)

        def handle(msg):
            m = msg.get("method")
            p = msg.get("params", {})
            if m == "Runtime.consoleAPICalled":
                text = " ".join(str(a.get("value", a.get("description", ""))) for a in p.get("args", []))
                print(f"[console.{p.get('type')}] {text[:400]}")
            elif m == "Runtime.exceptionThrown":
                d = p.get("exceptionDetails", {})
                print("[exception]", d.get("text"), (d.get("exception") or {}).get("description", "")[:800])
            elif m == "Network.webSocketFrameReceived":
                print("[ws<]", p["response"]["payloadData"][:300])
            elif m == "Network.webSocketFrameSent":
                print("[ws>]", p["response"]["payloadData"][:300])

        if mode == "eval":
            r = await call("Runtime.evaluate", {"expression": arg, "returnByValue": True, "awaitPromise": True})
            print(json.dumps(r.get("result"), ensure_ascii=False)[:4000])
            return
        await call("Runtime.enable")
        if mode == "console":
            await call("Page.reload")
        if mode == "wsframes":
            await call("Network.enable")
        try:
            async with asyncio.timeout(float(arg or 20)):
                while True:
                    handle(json.loads(await ws.recv()))
        except TimeoutError:
            pass


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else ""))
