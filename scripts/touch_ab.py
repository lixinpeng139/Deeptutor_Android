"""A/B the touch layer on a real touch device: audit every route with the built
touch rules active, then with them switched off (their @media rules emptied
in the live CSSOM), using the same effective-hit-area audit for both.

usage: python touch_ab.py <ws-url> [route ...]
"""

import asyncio
import json
from pathlib import Path
import sys

import websockets

sys.path.insert(0, str(Path(__file__).parent))
from touch_emulate import AUDIT, ROUTES, Session  # noqa: E402

# Disable every `(hover: none) and (pointer: coarse)` block, i.e. this layer.
DISABLE = """(() => {
  let n = 0;
  for (const sheet of document.styleSheets) {
    let rules; try { rules = sheet.cssRules; } catch { continue; }
    for (let i = rules.length - 1; i >= 0; i--) {
      const r = rules[i];
      if (r instanceof CSSMediaRule && /hover:\\s*none/.test(r.conditionText) && /pointer:\\s*coarse/.test(r.conditionText)) {
        sheet.deleteRule(i); n++;
      }
    }
  }
  return n;
})()"""


async def main(url, routes):
    async with websockets.connect(url, max_size=None) as ws:
        s = Session(ws)
        totals = {"on": [0, 0, 0], "off": [0, 0, 0]}
        for route in routes or ROUTES:
            row = []
            for mode in ("on", "off"):
                await s.go(route, 5)
                if mode == "off":
                    await s.eval(DISABLE)
                    await asyncio.sleep(0.5)
                d = json.loads(await s.eval(AUDIT))
                for i, k in enumerate(("total", "small", "tiny")):
                    totals[mode][i] += d[k]
                row.append(f"{mode}: small<40={d['small']:>3} tiny<28={d['tiny']:>3}")
            print(f"{route:<18} total={d['total']:>3}   " + "   ".join(reversed(row)))
        for mode in ("off", "on"):
            t = totals[mode]
            print(f"ALL {mode:<3}: total={t[0]} small<40={t[1]} ({t[1] * 100 // max(1, t[0])}%) tiny<28={t[2]}")
        await s.go("/chat", 2)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], sys.argv[2:]))
