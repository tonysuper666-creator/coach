"""Contact sheets for the manual review: each formula is Chromium (top) over Painter (bottom), labelled with id and ink gap.
Usage: review-sheets.py [per_sheet]. Output: ~/Coach/corpus/render/sheets/sheet-NN.png (not in Git)."""
import json, os, sys
from pathlib import Path
from PIL import Image, ImageDraw

root = Path(os.environ.get('COACH_RENDER_CORPUS', str(Path.home() / 'Coach/corpus/render')))
per = int(sys.argv[1]) if len(sys.argv) > 1 else 10
rows = [r for r in json.loads((root / 'comparison.json').read_text())['rows'] if not r['auto_consistent']]
out = root / 'sheets'; out.mkdir(exist_ok=True)
for old in out.glob('sheet-*.png'): old.unlink()
for n in range(0, len(rows), per):
    chunk = rows[n:n + per]; tiles = []
    for r in chunk:
        a = Image.open(root / 'browser' / f"{r['id']}.png").convert('RGB'); b = Image.open(root / 'painter' / f"{r['id']}.png").convert('RGB')
        t = Image.new('RGB', (max(a.width, 160), a.height * 2 + 26), '#eeeeee')
        ImageDraw.Draw(t).text((2, 1), f"#{r['id']}  gap={r['ink_missing_pixels']}+{r['ink_extra_pixels']}  ink={r['ink_ratio']:.2f}  top=Chromium bottom=Painter", fill='#cc0000')
        t.paste(a, (0, 14)); t.paste(b, (0, 18 + a.height)); tiles.append(t)
    sheet = Image.new('RGB', (max(t.width for t in tiles), sum(t.height + 6 for t in tiles)), 'white'); y = 0
    for t in tiles: sheet.paste(t, (0, y)); y += t.height + 6
    sheet.save(out / f'sheet-{n // per + 1:02d}.png')
print(len(rows), 'formulas ->', len(list(out.glob('sheet-*.png'))), 'sheets')
