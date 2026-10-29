"""Mutation test for the compare.py ink-gap diagnostic (any gap pixel = report; deleted band / 3px shift must be caught), on real Painter rasters (needs ~/Coach/corpus/render/painter)."""
import json, os, sys
from pathlib import Path
import numpy as np
from PIL import Image
sys.path.insert(0, str(Path(__file__).resolve().parent))
from inkgap import ink_gap

root = Path(os.environ.get('COACH_RENDER_CORPUS', str(Path.home() / 'Coach/corpus/render')))
# Expert's synthetic case: a one-pixel-high minus sign versus blank.
ga = np.zeros((20, 40), int); ga[10, 5:35] = 255
assert ink_gap(ga, np.zeros_like(ga)).sum() == 30, 'deleted minus must be reported'
assert ink_gap(ga, np.roll(ga, 1, axis=0)).sum() == 0, '1px sub-pixel shift must not be reported'
assert ink_gap(ga, np.roll(ga, 3, axis=0)).sum() == 30, '3px misplacement must be reported'
rows = json.loads((root / 'comparison.json').read_text())['rows']
fails = []
for r in rows:
    g = np.max(255 - np.array(Image.open(root / 'painter' / f"{r['id']}.png").convert('RGB')).astype(int), axis=2)
    cols = np.where((g >= 128).any(axis=0))[0]
    # Delete one glyph-sized column band in the middle of the ink, or shift the whole picture by 3px.
    mid = cols[len(cols) // 2]; deleted = g.copy(); deleted[:, max(0, mid - 4):mid + 4] = 0
    shifted = np.roll(g, 3, axis=1)
    for name, mutant in (('delete', deleted), ('shift3', shifted)):
        gap = int(ink_gap(g, mutant).sum() + ink_gap(mutant, g).sum())
        if gap == 0: fails.append((r['id'], name, gap))
print(json.dumps({'formulas': len(rows), 'mutations_missed': len(fails), 'examples': fails[:10]}))
sys.exit(1 if fails else 0)
