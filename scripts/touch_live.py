"""Hot-swap web/app/touch.css into the running WebView (touch media emulated)
and audit, so CSS iterations do not need a Next.js rebuild.

usage: python touch_live.py <ws-url> <touch.css> [route ...]
"""

import asyncio
import json
from pathlib import Path
import sys

import websockets

sys.path.insert(0, str(Path(__file__).parent))
from touch_emulate import AUDIT, ROUTES, Session  # noqa: E402

INJECT = """(css => {
  let s = document.getElementById('dt-touch-live');
  if (!s) { s = document.createElement('style'); s.id = 'dt-touch-live'; document.head.appendChild(s); }
  s.textContent = css;
  return s.sheet ? s.sheet.cssRules.length : -1;
})(%s)"""


async def main(url, css_path, routes):
    css = Path(css_path).read_text(encoding="utf-8")
    async with websockets.connect(url, max_size=None) as ws:
        s = Session(ws)
        await s.touch()
        for route in routes or ROUTES:
            await s.go(route)
            await s.eval(INJECT % json.dumps(css))
            await asyncio.sleep(0.5)
            d = json.loads(await s.eval(AUDIT))
            print(f"== {route}: total={d['total']} small<40={d['small']} tiny<28={d['tiny']} hidden={d['hidden']}")
            for line in d["samples"][:12]:
                print("   ", line)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3:]))
