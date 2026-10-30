"""Measure inline-formula baseline error from the Android baseline.png (420 dpi => 2.625 px/dp).

The sheet is one line `40 [40] 12 [12] 3 [3] 40` where bracketed items are MathJax formulas painted
by the shared Painter. Digits rest on the baseline in both fonts, so the ink bottom of each formula
is compared with the ink bottom of the text digits beside it (sub-pixel, by coverage interpolation).
"""
import json, os, sys
from pathlib import Path
import numpy as np
from PIL import Image

root = Path(os.environ.get('COACH_RENDER_CORPUS', str(Path.home() / 'Coach/corpus/render'))) / 'android'
density = 2.625
img = 255 - np.array(Image.open(root / 'baseline.png').convert('L')).astype(float)
rows = np.where(img.max(axis=1) > 64)[0]
# First text line: rows from the first ink row until a blank gap of >=8px.
top = rows[0]; end = top
for r in rows:
    if r - end > 8: break
    end = r
band = img[top:end + 1 + 6]
cols = np.where(band.max(axis=0) > 64)[0]
segments = []; start = cols[0]; prev = cols[0]
for c in cols[1:]:
    if c - prev > 14: segments.append((start, prev)); start = c
    prev = c
segments.append((start, prev))
def bottom(seg):
    cov = band[:, seg[0]:seg[1] + 1].max(axis=1) / 255.0
    ys = np.where(cov > .5)[0]; y = ys[-1]
    nxt = cov[y + 1] if y + 1 < len(cov) else 0
    return top + y + .5 + min(max(nxt, 0), .5)  # coverage of the partial pixel below the half-point
bottoms = [bottom(s) for s in segments]
result = {'segments': len(segments), 'bottoms_px': [float(round(b, 2)) for b in bottoms]}
if len(segments) == 7:
    pairs = [(0, 1), (2, 3), (4, 5)]
    errs = []
    for t, m in pairs:
        errs.append(abs(bottoms[m] - (bottoms[t] + bottoms[t + 2]) / 2) / density)
    result['baseline_error_dp'] = [float(round(e, 3)) for e in errs]
    result['max_baseline_error_dp'] = float(round(max(errs), 3))
    result['pass_le_1dp'] = bool(max(errs) <= 1)
print(json.dumps(result))
sys.exit(0 if result.get('pass_le_1dp') else 1)
