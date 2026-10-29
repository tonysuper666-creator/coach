"""Compare identical MathJax SVGs in Chromium and the real common Painter (32 px/em)."""
import argparse, json, math, os, sys
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

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inkgap import ink_gap
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
        diff = delta > 16
        # Bounding box of the ink drawn by either renderer (non-white at >16/255).
        ink = (np.max(255-a,axis=2) > 16) | (np.max(255-b,axis=2) > 16)
        ys, xs = np.where(ink)
        box_px = int((ys.max()-ys.min()+1)*(xs.max()-xs.min()+1)) if len(ys) else 1
        pct = float(diff.sum()*100/box_px)
        # Diagnostic (does not replace the 2% rule): ink coverage. An ink pixel (darkness >= 128) of one rendering
        # with no ink (>= 64) within 1px in the other counts as missing/extra. Deleted or added strokes always count.
        ga, gb = np.max(255-a,axis=2), np.max(255-b,axis=2)
        miss, extra = ink_gap(ga, gb), ink_gap(gb, ga)
        tol_pct = float((miss.sum()+extra.sum())*100/box_px)
        tol = miss | extra
        ia, ib = (255-a).max(axis=2).astype(float), (255-b).max(axis=2).astype(float)
        ratio = float(ib.sum()/max(ia.sum(),1))
        win = 16; per = max(ia.sum()/max(ia.shape[1]//win,1),1)
        dev = max([abs(ia[:,k:k+win].sum()-ib[:,k:k+win].sum())/max(ia[:,k:k+win].sum(),ib[:,k:k+win].sum(),1)
                   for k in range(0,ia.shape[1]-win+1,win) if max(ia[:,k:k+win].sum(),ib[:,k:k+win].sum()) > .02*per] or [0])
        row_report = {'ink_ratio':ratio,'max_window_ink_deviation':dev,'id':row['id'],'strict_different_pixels_percent':exact,'diff_over_16_pixels':int(diff.sum()),
            'bbox_pixels':box_px,'diff_over_16_percent_of_bbox':pct,'auto_consistent':pct <= 2,'ink_missing_pixels':int(miss.sum()),'ink_extra_pixels':int(extra.sum()),'ink_gap_percent_of_bbox':tol_pct,
            'has_chinese':bool(re.search('[\u3400-\u9fff]',row['tex']))}
        if not row_report['auto_consistent']:
            (root / 'review').mkdir(exist_ok=True)
            heat = np.full(a.shape,255,dtype=np.uint8); heat[diff] = [255,0,0]
            sheet = np.concatenate([a.astype(np.uint8), b.astype(np.uint8), heat],axis=1)
            Image.fromarray(sheet).save(root / 'review' / f'{row["id"]}.png')
        reports.append(row_report)
    browser.close()
auto = sum(r['auto_consistent'] for r in reports)
report = {'em_px':32,'pixel_rule':'A pixel differs when any RGB channel differs by >16/255; a formula is consistent when differing pixels <=2% of the ink bounding box (union of both renderers). Strict any-channel percentage is reported per row for reference.',
    'formulas':len(rows),'auto_consistent':auto,'auto_consistent_percent':auto*100/len(rows),
    'ink_gap_zero_pixels_non_cjk':sum(r['ink_missing_pixels']+r['ink_extra_pixels'] == 0 for r in reports if not r['has_chinese']),'non_cjk_formulas':sum(not r['has_chinese'] for r in reports),'cjk_with_ink_gap':[r['id'] for r in reports if r['has_chinese'] and r['ink_missing_pixels']+r['ink_extra_pixels']],
    'ink_gap_le_2pct':sum(r['ink_gap_percent_of_bbox'] <= 2 for r in reports),
    'ink_ratio_outside_0.9_1.1':sum(not .9 <= r['ink_ratio'] <= 1.1 for r in reports),'max_window_ink_deviation':max(r['max_window_ink_deviation'] for r in reports),
    'needs_review':[r['id'] for r in reports if not r['auto_consistent']],'rows':reports}
(root / 'comparison.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k not in ('rows','needs_review')}), 'needs_review=%d' % len(report['needs_review']))
