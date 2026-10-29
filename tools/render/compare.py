"""Compare identical MathJax SVGs in Chromium and the real common Painter (32 px/em)."""
import argparse, json, math, os
from pathlib import Path
import numpy as np
from PIL import Image
from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument('--corpus', type=Path, default=Path.home() / 'Coach/corpus/render')
args = parser.parse_args()
root = args.corpus
rows = json.loads((root / 'formulas.json').read_text())
(root / 'browser').mkdir(exist_ok=True)
reports = []
with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=os.environ.get("COACH_CHROMIUM_BIN"))
    page = browser.new_page(device_scale_factor=1)
    for row in rows:
        import re
        box = list(map(float, re.search(r'viewBox="([^"]+)"', row['svg'])[1].split()))
        w, h = math.ceil(box[2] * .032), math.ceil(box[3] * .032)
        page.set_viewport_size({'width':max(1,w),'height':max(1,h)})
        page.set_content('<html><head><style>html,body{margin:0;background:white;color:black;font-size:32px}svg{display:block}</style></head><body>' + row['svg'] + '</body></html>')
        page.locator('svg').first.evaluate('(s,b)=>{s.setAttribute("width",b[0]);s.setAttribute("height",b[1])}', [box[2]*.032,box[3]*.032])
        target = root / 'browser' / f'{row["id"]}.png'
        page.screenshot(path=str(target))
        a = np.array(Image.open(target).convert('RGB')).astype(int)
        b = np.array(Image.open(root / 'painter' / f'{row["id"]}.png').convert('RGB')).astype(int)
        assert a.shape == b.shape, (row['id'],a.shape,b.shape)
        delta = np.max(np.abs(a-b),axis=2)
        exact = float(np.mean(delta != 0)*100)
        threshold16 = float(np.mean(delta > 16)*100)
        reports.append({'id':row['id'],'different_pixels_percent':exact,'delta_over_16_percent':threshold16,'pass':exact <= 2,'has_chinese':bool(re.search('[\u3400-\u9fff]',row['tex']))})
    browser.close()
report = {'em_px':32,'pixel_rule':'Any RGB channel differs; denominator is whole image; tolerance 16 reported separately, not used to pass',
    'formulas':len(rows),'passed':sum(r['pass'] for r in reports),'consistent_percent':sum(r['pass'] for r in reports)*100/len(rows),'rows':reports}
(root / 'comparison.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k != 'rows'}))
