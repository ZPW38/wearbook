#!/usr/bin/env python3
"""Convert Material Symbols SVGs to Android vector drawables.

Material Symbols ship with viewBox="0 -960 960 960" (y spans -960..0).
Android <vector> viewports start at (0,0), so we MUST translate every y
coordinate by +960, otherwise the whole glyph is drawn above the visible
area and the icon silently disappears. We also carry over fill-rule so
hollow glyphs (settings gear, book pages) keep their cutouts.
"""
import os, re, glob

SRC = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(SRC, "..", "..", "app", "src", "main", "res", "drawable")
os.makedirs(OUT, exist_ok=True)

# y-shift to move viewBox "0 -960 960 960" into "0 0 960 960"
SHIFT_Y = 960

_NUM = r"[-+]?(?:\d+\.?\d*|\.\d+)"
_CMD_RE = re.compile(r"([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)")
_NUM_RE = re.compile(_NUM)


def transform_d(d):
    """Translate every ABSOLUTE y coordinate by SHIFT_Y.

    Material Symbols use viewBox y in [-960,0]; Android <vector> expects
    [0,960]. Only ABSOLUTE commands (uppercase) carry absolute y; relative
    (lowercase) commands are *offsets* and must NOT be shifted, otherwise the
    glyph shape is destroyed.
    """
    out = []
    for idx, (cmd, args) in enumerate(_CMD_RE.findall(d)):
        nums = [float(x) for x in _NUM_RE.findall(args)]
        c = cmd.upper()
        lower = cmd.islower()
        if c == "Z":
            out.append(cmd)
            continue
        if c in ("M", "L", "T"):
            step = 2
        elif c in ("C", "S", "Q"):
            step = 6 if c == "C" else 4
        elif c == "H":
            step = 1
        elif c == "V":
            step = 1
        elif c == "A":
            step = 7
        else:
            step = 2
        groups = [nums[i:i + step] for i in range(0, len(nums), step)]
        new = []
        for gi, g in enumerate(groups):
            gg = list(g)
            if c == "H":
                pass  # x only, never shifted
            elif c == "V":
                if not lower:
                    gg[0] += SHIFT_Y
            elif c == "A":
                if not lower:
                    gg[5] += SHIFT_Y  # endpoint y
            elif c == "M":
                # Per SVG spec: the FIRST moveto of a path is ALWAYS absolute,
                # even when written as lowercase "m" (relative to 0,0). Later
                # relative "m" subpaths are true offsets -> must NOT be shifted.
                if (not lower) or (idx == 0 and gi == 0):
                    gg[1] += SHIFT_Y
            elif c in ("L", "T"):
                if not lower:
                    gg[1] += SHIFT_Y
            elif c == "C":
                if not lower:
                    gg[1] += SHIFT_Y
                    gg[3] += SHIFT_Y
                    gg[5] += SHIFT_Y
            elif c in ("S", "Q"):
                if not lower:
                    gg[1] += SHIFT_Y
                    gg[3] += SHIFT_Y
            new.extend(gg)
        out.append(cmd + " ".join(_fmt(n) for n in new))
    return " ".join(out)


def _fmt(n):
    if n == int(n):
        return str(int(n))
    return f"{n:.3f}".rstrip("0").rstrip(".")


def parse_svg(path):
    txt = open(path, encoding="utf-8").read()
    vb = re.search(r'viewBox="([^"]+)"', txt)
    if vb:
        nums = [float(x) for x in vb.group(1).split()]
        vw, vh = nums[2], nums[3]
    else:
        vw = vh = 960.0
    # capture fill-rule if present
    filltype = None
    fr = re.search(r'fill-rule="([^"]+)"', txt)
    if fr and fr.group(1).lower() == "evenodd":
        filltype = "evenOdd"
    # path may use single or double quotes
    ds = re.findall(r"<path[^>]*\sd=\"([^\"]+)\"", txt)
    if not ds:
        ds = re.findall(r"<path\sd=\"([^\"]+)\"", txt)
    # also match paths written with 'd=' style quoting not needed
    paths = []
    for raw in ds:
        paths.append((transform_d(raw), filltype))
    return vw, vh, paths


def write_vd(name, vw, vh, paths, fill="#FF000000"):
    paths_xml = ""
    for d, ft in paths:
        ft_attr = f'\n            android:fillType="{ft}"' if ft else ""
        paths_xml += (
            "    <path\n"
            f'        android:fillColor="{fill}"{ft_attr}\n'
            f'        android:pathData="{d}" />\n'
        )
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        f'    android:viewportWidth="{int(vw)}"\n'
        f'    android:viewportHeight="{int(vh)}">\n'
        f"{paths_xml}"
        "</vector>\n"
    )
    with open(os.path.join(OUT, f"ic_{name}.xml"), "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote ic_%s.xml (%d path(s))" % (name, len(paths)))


for svg in sorted(glob.glob(os.path.join(SRC, "*.svg"))):
    base = os.path.basename(svg)[:-4]
    if base in ("render",):
        continue
    vw, vh, paths = parse_svg(svg)
    if not paths:
        print("SKIP (no path):", base)
        continue
    write_vd(base, vw, vh, paths)

# Launcher foreground: menu_book, white, centered inside the adaptive safe zone.
vw, vh, paths = parse_svg(os.path.join(SRC, "menu_book.svg"))
d = paths[0][0]
launcher = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:width="108dp"\n'
    '    android:height="108dp"\n'
    f'    android:viewportWidth="{int(vw)}"\n'
    f'    android:viewportHeight="{int(vh)}">\n'
    '    <group android:pivotX="480" android:pivotY="480" android:scaleX="0.66" android:scaleY="0.66">\n'
    '        <path\n'
    '            android:fillColor="#FFFFFFFF"\n'
    f'            android:pathData="{d}" />\n'
    '    </group>\n'
    '</vector>\n'
)
with open(os.path.join(OUT, "ic_launcher_foreground.xml"), "w", encoding="utf-8") as f:
    f.write(launcher)
print("wrote ic_launcher_foreground.xml (launcher)")
