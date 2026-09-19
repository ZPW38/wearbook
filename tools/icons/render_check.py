#!/usr/bin/env python3
"""Render original Material Symbols SVG vs my generated vector path side by side,
then screenshot with headless Chrome so we can visually confirm they match."""
import os, re, glob, subprocess

SRC = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(SRC, "..", "..", "app", "src", "main", "res", "drawable")
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"


def orig_path(svg):
    txt = open(svg, encoding="utf-8").read()
    m = re.search(r'<path[^>]*\sd="([^"]+)"', txt) or re.search(r'<path\sd="([^"]+)"', txt)
    return m.group(1)


def mine_path(xml):
    return re.search(r'android:pathData="([^"]+)"', open(xml, encoding="utf-8").read()).group(1)


cells = []
for svg in sorted(glob.glob(os.path.join(SRC, "*.svg"))):
    base = os.path.basename(svg)[:-4]
    if base == "render":
        continue
    op = orig_path(svg)
    vd = os.path.join(OUT, f"ic_{base}.xml")
    if not os.path.exists(vd):
        continue
    mp = mine_path(vd)
    cells.append(f'''<div class="item"><div class="name">{base}</div>
      <div class="pair">
        <div class="cell"><div class="lbl">ORIGINAL</div><svg viewBox="0 -960 960 960" width="84" height="84"><path d="{op}" fill="#111"/></svg></div>
        <div class="cell"><div class="lbl">MINE</div><svg viewBox="0 0 960 960" width="84" height="84"><path d="{mp}" fill="#111"/></svg></div>
      </div></div>''')

# launcher preview: menu_book path inside a group scaled 0.66, white on teal rounded bg
mp = mine_path(os.path.join(OUT, "ic_menu_book.xml"))
cells.append(f'''<div class="item"><div class="name">LAUNCHER</div>
  <div class="pair">
    <div class="cell"><div class="lbl">app icon</div>
      <svg viewBox="0 0 960 960" width="84" height="84">
        <rect x="0" y="0" width="960" height="960" rx="200" fill="#00695C"/>
        <g transform="translate(480 480) scale(0.66) translate(-480 -480)">
          <path d="{mp}" fill="#FFFFFF"/>
        </g>
      </svg></div>
  </div></div>''')

html = f'''<html><head><meta charset="utf-8"><style>
body{{background:#fff;font-family:sans-serif;margin:0}}
.wrap{{display:flex;flex-wrap:wrap;padding:8px}}
.item{{border:1px solid #ddd;margin:6px;padding:6px}}
.name{{font-size:13px;text-align:center;font-weight:bold;margin-bottom:4px}}
.pair{{display:flex;gap:8px}}
.cell{{text-align:center}}
.lbl{{font-size:9px;color:#888;margin-bottom:2px}}
svg{{display:block;background:#fafafa;border:1px solid #eee}}
</style></head><body><div class="wrap">{''.join(cells)}</div></body></html>'''

html_path = os.path.join(SRC, "check.html")
open(html_path, "w", encoding="utf-8").write(html)
print("wrote", html_path, "items:", len(cells))

png = os.path.join(SRC, "check.png")
subprocess.run([
    CHROME, "--headless", "--disable-gpu", "--no-sandbox",
    "--hide-scrollbars", "--force-device-scale-factor=2",
    f"--window-size=1100,900", f"--screenshot={png}",
    "file://" + html_path.replace("\\", "/"),
], check=False)
print("screenshot ->", png, os.path.exists(png))
