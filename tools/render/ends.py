"""Check that every formula row that scrolled between the start and end screenshots really reaches its right end.

For each pair <name>.png / <name>-end.png (Android, 420dpi = 2.625 px/dp, 24dp page padding) the rows that differ are the
scrolled formulas. In the end screenshot their rightmost ink must touch the right content edge (within 16px): ink that stops
short means the scroll range ended before the formula did; no scrolled row means nothing was wider than the page.
This is a reachability check, not a substitute for looking at the last symbol.
"""
import json, os, sys
from pathlib import Path
import numpy as np
from PIL import Image

root = Path(os.environ.get('COACH_RENDER_CORPUS', str(Path.home() / 'Coach/corpus/render'))) / 'android'
pad_px = round(24 * 2.625)
pairs = [(f'chinese-{n}', f'chinese-{n}-end') for n in range(1, 8)] + [(f'wide-{s}-start', f'wide-{s}-end') for s in ('outer', 'pad')]
report, bad = {}, 0
for a_name, b_name in pairs:
    a = 255 - np.array(Image.open(root / f'{a_name}.png').convert('L')).astype(int)
    b = 255 - np.array(Image.open(root / f'{b_name}.png').convert('L')).astype(int)
    h, w = a.shape; edge = w - pad_px
    changed = np.where((np.abs(a - b) > 40).any(axis=1))[0]
    rows, start = [], None
    for y in changed:  # group changed pixel rows into bands
        if start is None: start, prev = y, y
        elif y - prev > 12: rows.append((start, prev)); start = y
        prev = y
    if start is not None: rows.append((start, prev))
    out = []
    for y0, y1 in rows:
        cols = np.where((b[y0:y1 + 1] > 128).any(axis=0))[0]
        right = int(cols.max()) if len(cols) else -1
        ok = edge - 16 <= right <= edge + 2
        bad += not ok
        out.append({'y': [int(y0), int(y1)], 'rightmost_ink_px': right, 'content_edge_px': int(edge), 'reaches_end': bool(ok)})
    report[a_name] = out
print(json.dumps(report))
sys.exit(1 if bad else 0)
