// Touch audit: run in the page via CDP. For every visible interactive element,
// measures the *effective* hit area — how far from the element's centre a tap
// still lands on it (via elementFromPoint, so invisible ::after extensions and
// overlapping neighbours both count) — and reports anything under 40px.
(() => {
  const sel = 'a[href],button,[role=button],[role=menuitem],[role=tab],[role=option],[role=switch],[role=checkbox],input:not([type=hidden]),select,textarea,summary';
  const out = { total: 0, small: 0, tiny: 0, hidden: 0, samples: [], hiddenSamples: [] };
  const label = el => (el.getAttribute('aria-label') || el.title || el.textContent || el.tagName).trim().replace(/\s+/g, ' ').slice(0, 40);
  const hits = (el, x, y) => {
    if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return true; // screen edge: no neighbour can steal it
    const at = document.elementFromPoint(x, y);
    return !!at && (at === el || el.contains(at));
  };
  // Largest symmetric reach (px) along one axis, probing 2px steps up to 30px.
  const reach = (el, cx, cy, dx, dy) => {
    let r = 0;
    for (let d = 2; d <= 30; d += 2) {
      if (hits(el, cx + dx * d, cy + dy * d) && hits(el, cx - dx * d, cy - dy * d)) r = d; else break;
    }
    return r;
  };
  for (const el of document.querySelectorAll(sel)) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue;
    if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || cs.pointerEvents === 'none') continue;
    const cx = Math.min(Math.max(r.left + r.width / 2, 1), innerWidth - 1);
    const cy = Math.min(Math.max(r.top + r.height / 2, 1), innerHeight - 1);
    if (!hits(el, cx, cy)) continue; // covered by something else (closed popover etc.)
    out.total++;
    let o = el, faded = false;
    while (o && o !== document.body) { if (parseFloat(getComputedStyle(o).opacity) === 0) { faded = true; break; } o = o.parentElement; }
    if (faded) {
      out.hidden++;
      if (out.hiddenSamples.length < 15) out.hiddenSamples.push(label(el));
      continue;
    }
    const w = 2 * reach(el, cx, cy, 1, 0);
    const h = 2 * reach(el, cx, cy, 0, 1);
    const m = Math.min(w, h);
    if (m < 40) {
      out.small++;
      if (m < 28) out.tiny++;
      if (out.samples.length < 25) out.samples.push(`${w}x${h} (box ${Math.round(r.width)}x${Math.round(r.height)}) ${el.tagName.toLowerCase()} "${label(el)}"`);
    }
  }
  return JSON.stringify(out);
})()
