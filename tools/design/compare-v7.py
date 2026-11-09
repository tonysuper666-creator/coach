"""Pair approved HTML references with actual Android JVM captures, without resizing.

python tools/design/compare-v7.py D:/agent/design/ui-v7/shots androidApp/build/screens/v7 --output ../v7-comparisons
"""
from pathlib import Path
import argparse
import hashlib
import json
import zipfile
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser()
parser.add_argument('references', type=Path)
parser.add_argument('actual', type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--sha', default='working-tree')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
cases = [(device, scene, mode) for device in ('inner', 'outerL', 'outer') for scene in ('week', 'read', 'quiz') for mode in ('light', 'dark')]
cases += [('inner', 'focus', mode) for mode in ('light', 'dark')]
manifest = {'sourceCommit': args.sha, 'renderer': 'Android Compose / Robolectric native graphics / Roborazzi',
            'content': 'Synthetic fixtures only. Course-specific wording is not a pixel equality baseline.', 'cases': []}
font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf', 26)
for device, scene, mode in cases:
    name = f'{device}-{scene}-{mode}.png'
    reference = args.references / name
    actual = args.actual / name
    with Image.open(reference) as left, Image.open(actual) as right:
        if left.size != right.size:
            raise ValueError(f'{name}: reference {left.size} != Android {right.size}; do not resize evidence')
        width, height = left.size
        canvas = Image.new('RGB', (width * 2 + 16, height + 48), '#f0f0f0')
        canvas.paste(left.convert('RGB'), (0, 48))
        canvas.paste(right.convert('RGB'), (width + 16, 48))
        draw = ImageDraw.Draw(canvas)
        draw.text((16, 8), f'Approved reference | {name}', fill='#111111', font=font)
        draw.text((width + 32, 8), 'Android JVM capture | synthetic course', fill='#111111', font=font)
        target = args.output / name
        canvas.save(target)
        manifest['cases'].append({'name': name, 'width': width, 'height': height,
            'referenceSha256': hashlib.sha256(reference.read_bytes()).hexdigest(),
            'actualSha256': hashlib.sha256(actual.read_bytes()).hexdigest()})
(args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
with zipfile.ZipFile(args.output.with_suffix('.zip'), 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for file in args.output.iterdir():
        archive.write(file, file.name)
print(f'Created {len(cases)} full-size pairs and manifest: {args.output.with_suffix(".zip").name}')
