"""Behaviour checks for the touch adaptation, driven with real input on a device.

Touch media is emulated over CDP (the emulator reports a fine pointer), and
gestures are sent through the DevTools Input domain as genuine touch events.

usage: python touch_checks.py <ws-url> <adb-serial>
"""

import asyncio
import json
from pathlib import Path
import subprocess
import sys

import websockets

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from touch_emulate import ADB, Session  # noqa: E402

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)


async def swipe(s, x0, y0, x1, y1, steps=8):
    await s.call("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": x0, "y": y0}]})
    for i in range(1, steps + 1):
        x = x0 + (x1 - x0) * i / steps
        y = y0 + (y1 - y0) * i / steps
        await s.call("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [{"x": x, "y": y}]})
        await asyncio.sleep(0.016)
    await s.call("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
    await asyncio.sleep(0.5)


async def tap(s, x, y):
    await s.call("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": x, "y": y}]})
    await asyncio.sleep(0.05)
    await s.call("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
    await asyncio.sleep(0.6)


def adb(serial, *args):
    return subprocess.run([str(ADB), "-s", serial, *args], capture_output=True, text=True).stdout


async def drawer_open(s):
    return await s.eval("!!document.querySelector(\"[data-android-back='close-drawer']\")")


async def main(url, serial):
    async with websockets.connect(url, max_size=None) as ws:
        s = Session(ws)
        await s.touch()
        await s.go("/chat", 6)
        h = await s.eval("innerHeight")
        w = await s.eval("innerWidth")

        # 1. Edge swipe opens the drawer; swipe left closes it.
        await swipe(s, 4, h * 0.5, 160, h * 0.52)
        check("edge swipe opens navigation drawer", await drawer_open(s))
        await swipe(s, 240, h * 0.5, 40, h * 0.5)
        check("swipe left closes navigation drawer", not await drawer_open(s))

        # A vertical drag from the edge is a scroll, not a drawer.
        await swipe(s, 6, h * 0.3, 20, h * 0.7)
        check("vertical edge drag does not open drawer", not await drawer_open(s))

        # A swipe that starts mid-screen does not open the drawer.
        await swipe(s, w * 0.4, h * 0.5, w * 0.9, h * 0.5)
        check("mid-screen swipe does not open drawer", not await drawer_open(s))

        # 2. Back: page bridge closes the drawer first.
        await swipe(s, 4, h * 0.5, 160, h * 0.52)
        consumed = await s.eval("window.__deeptutorBack ? window.__deeptutorBack() : 'missing'")
        await asyncio.sleep(0.5)
        check("Back closes open drawer", consumed is True and not await drawer_open(s), f"returned {consumed}")
        consumed = await s.eval("window.__deeptutorBack()")
        check("Back with nothing open is not consumed", consumed is False, f"returned {consumed}")

        # 3. Back closes an open menu (capability picker) via Escape.
        opened = await s.eval("""(() => {
            const b = [...document.querySelectorAll('button[aria-haspopup]')].find(e => e.getBoundingClientRect().width > 0
                && e.closest('form, [class*=Composer], [class*=composer]'));
            if (!b) return 'none';
            b.click(); return b.getAttribute('aria-label') || b.textContent.trim().slice(0, 20);
        })()""")
        await asyncio.sleep(0.5)
        menu_before = await s.eval("[...document.querySelectorAll('[role=menu],[role=listbox],[role=dialog]')].filter(e=>{const r=e.getBoundingClientRect();return r.height>0&&r.left<innerWidth&&r.right>0&&!e.closest('[aria-hidden=true],[inert]')}).length")
        consumed = await s.eval("window.__deeptutorBack()")
        await asyncio.sleep(0.4)
        menu_after = await s.eval("[...document.querySelectorAll('[role=menu],[role=listbox],[role=dialog]')].filter(e=>{const r=e.getBoundingClientRect();return r.height>0&&r.left<innerWidth&&r.right>0&&!e.closest('[aria-hidden=true],[inert]')}).length")
        check("Back closes an open composer menu", menu_before > 0 and menu_after < menu_before and consumed is True,
              f"opened {opened!r}: {menu_before} -> {menu_after}, consumed {consumed}")

        # 4. Enter in the composer inserts a newline on touch (on-screen keyboard).
        await s.eval("document.querySelector('textarea').focus()")
        for ch in "ab":
            await s.call("Input.insertText", {"text": ch})
        await s.call("Input.dispatchKeyEvent", {"type": "keyDown", "key": "Enter", "code": "Enter",
                                                "windowsVirtualKeyCode": 13, "text": "\r"})
        await s.call("Input.dispatchKeyEvent", {"type": "keyUp", "key": "Enter", "code": "Enter", "windowsVirtualKeyCode": 13})
        await asyncio.sleep(0.4)
        value = await s.eval("document.querySelector('textarea').value")
        sent = await s.eval("location.pathname")
        check("Enter does not send on touch", sent == "/chat" and value.startswith("ab"), f"value={value!r} path={sent}")
        await s.eval("(()=>{const t=document.querySelector('textarea');const set=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;set.call(t,'');t.dispatchEvent(new Event('input',{bubbles:true}))})()")

        # 5. Hover-only content is visible.
        hidden = await s.eval("""(() => [...document.querySelectorAll('.group .opacity-0[class*="group-hover:opacity-100"]')]
            .filter(e => e.getBoundingClientRect().width > 0 && getComputedStyle(e).opacity === '0').length)()""")
        check("no hover-only controls stay invisible", hidden == 0, f"{hidden} hidden")

        # 6. Inputs are >= 16px so focusing them never zooms.
        small = await s.eval("""(() => [...document.querySelectorAll('input:not([type=checkbox]):not([type=radio]):not([type=range]):not([type=file]),textarea,select')]
            .filter(e => e.getBoundingClientRect().width > 0 && parseFloat(getComputedStyle(e).fontSize) < 16).length)()""")
        check("text inputs are at least 16px", small == 0, f"{small} smaller")

        # 7. Tap feedback and no tap highlight flash.
        tap_hl = await s.eval("getComputedStyle(document.documentElement).webkitTapHighlightColor")
        check("tap highlight disabled", tap_hl in ("rgba(0, 0, 0, 0)", "transparent"), tap_hl)

        # 8. Resize handles claim the gesture.
        bad = await s.eval("""(() => [...document.querySelectorAll('.cursor-col-resize,.cursor-ew-resize,[role=separator][aria-orientation]')]
            .filter(e => getComputedStyle(e).touchAction !== 'none').length)()""")
        check("drag handles use touch-action:none", bad == 0, f"{bad} without")

        # 9. A real tap on a small icon lands on it (hit-area extension).
        target = await s.eval("""(() => {
            const b = [...document.querySelectorAll('button')].find(e => { const r = e.getBoundingClientRect();
                return r.width > 0 && r.width <= 36 && r.left >= 0 && r.right <= innerWidth && r.top > 40
                    && r.top < innerHeight - 80 && e.getAttribute('aria-label') && !e.closest('[inert],[aria-hidden=true]')
                    && document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)?.closest('button') === e; });
            if (!b) return null; const r = b.getBoundingClientRect();
            window.__tapHit = null; b.addEventListener('click', () => window.__tapHit = b.getAttribute('aria-label'), { once: true, capture: true });
            return { label: b.getAttribute('aria-label'), x: r.left + r.width / 2, y: r.bottom + 5 };
        })()""")
        if target:
            await tap(s, target["x"], target["y"])
            hit = await s.eval("window.__tapHit")
            await s.eval("document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))")
            check("tap 5px below a small icon still hits it", hit == target["label"], f"{target['label']!r} got {hit!r}")
        # 10. Touch text selection in a reply shows the passage actions.
        await s.go("/chat", 5)
        await s.eval("document.querySelector('textarea').focus()")
        await s.call("Input.insertText", {"text": "Pythagorean theorem"})
        await s.eval("""(() => { const b = [...document.querySelectorAll('button')].find(e => e.getAttribute('aria-label') === 'Send'); b && b.click(); })()""")
        reply = None
        for _ in range(40):
            await asyncio.sleep(0.5)
            done = await s.eval("[...document.querySelectorAll('button')].some(b => /Copy/i.test(b.getAttribute('aria-label') || ''))")
            reply = done and await s.eval("""(() => { const m = [...document.querySelectorAll('[data-chat-message-id]')].filter(e => [...e.querySelectorAll('p')].some(q => q.textContent.length > 20)).pop();
                const p = m && [...m.querySelectorAll('p')].find(e => e.textContent.length > 20); return p ? p.textContent.slice(0, 30) : null; })()""")
            if reply:
                break
        if reply:
            # A touch selection changes the selection without any mouseup.
            await s.eval("""(() => { const m = [...document.querySelectorAll('[data-chat-message-id]')].filter(e => [...e.querySelectorAll('p')].some(q => q.textContent.length > 20)).pop();
                const p = [...m.querySelectorAll('p')].find(e => e.textContent.length > 20);
                const t = p.firstChild; const r = document.createRange(); r.setStart(t, 0); r.setEnd(t, Math.min(12, t.length));
                const sel = getSelection(); sel.removeAllRanges(); sel.addRange(r); })()""")
            await asyncio.sleep(1.2)
            bar = await s.eval("""(() => [...document.querySelectorAll('button')].some(b => /Add to chat|Ask/.test(b.textContent || '')
                && b.getBoundingClientRect().height > 0))()""")
            check("touch selection shows passage actions", bar, f"reply {reply!r}")
            back = await s.eval("window.__deeptutorBack()")
            check("Back clears a text selection", back is True and await s.eval("getSelection().isCollapsed"))
        else:
            check("touch selection shows passage actions", False, "no reply arrived")
        await s.go("/chat", 3)

    passed = sum(1 for _, ok, _ in results if ok)
    print(f"\n{passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1], sys.argv[2])))
