#!/usr/bin/env node
// Exercise the real offline page and Python export with self-authored images only.
// Requires Python + Pillow and Playwright + a Chromium browser. No server or network.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {pathToFileURL} = require('node:url');

const args = process.argv.slice(2), options = {};
for (let i=0; i<args.length; i+=2) {
  if (!['--python','--playwright-module','--browser','--out'].includes(args[i]) || !args[i+1]) {
    throw Error('Usage: verify_review_comparison.cjs [--python PYTHON] [--playwright-module MODULE] [--browser CHROMIUM] [--out NEW_PRIVATE_DIR]');
  }
  options[args[i]] = args[i+1];
}
const python = options['--python'] || 'python3';
const {chromium} = require(options['--playwright-module'] || 'playwright');
const root = options['--out'] ? path.resolve(options['--out']) : fs.mkdtempSync(path.join(os.tmpdir(),'crop-comparison-'));
if (options['--out']) fs.mkdirSync(root, {mode:0o700});
const runPython = (script, ...args) => execFileSync(python, ['-c',script,__dirname,root,...args], {encoding:'utf8'});
const approved = 'COMPLETE_OBJECT_WITH_LABELS', pending = 'UNREVIEWED';
const checks = ['wholeObject','labels','noProseOrDuplicate'];

async function main() {
  runPython(`
import hashlib,json,sys
from pathlib import Path
from PIL import Image,ImageDraw
sys.path.insert(0,sys.argv[1])
import review_workspace as rw
root=rw.private(sys.argv[2]); previews=root/'previews'; previews.mkdir()
page=Image.new('RGB',(1000,700),'white'); d=ImageDraw.Draw(page)
d.text((40,30),'Self-authored comparison fixture',fill='black')
for x in (70,530):
 d.text((x,95),'Table: title',fill='black')
 d.rectangle((x,130,x+300,240),outline='black',width=3)
 d.text((x+10,155),'A    B    C',fill='black')
 d.text((x,265),'Source: self-authored fixture',fill='black')
for n in (1,2): page.save(previews/f'page-{n}.png')
for name,n,edges in [('a-complete',1,[40,80,410,300]),('b-inner',1,[50,100,400,260]),
 ('c-independent',1,[500,80,880,300]),('d-bridge',1,[300,120,650,250]),('e-other-page',2,[40,80,410,300])]:
 file=previews/f'{name}.png'; page.crop(edges).save(file)
 review=dict(schema=1,documentSha256='a'*64,pageTextSha256='b'*64,
  pngSha256=hashlib.sha256(file.read_bytes()).hexdigest(),decision='UNREVIEWED',
  selection=dict(zip(['left','top','right','bottom'],edges),page=n,kind='TABLE'))
 (previews/f'{name}.review.json').write_text(json.dumps(review))
assert rw.prepare(previews,root/'workspace')==dict(objects=5,pages=2,approved=0)
`);
  const browser = await chromium.launch({headless:true,...(options['--browser'] ? {executablePath:options['--browser']} : {})});
  try {
    const context = await browser.newContext({viewport:{width:1600,height:1000},colorScheme:'light',offline:true,acceptDownloads:true});
    context.setDefaultTimeout(15000);
    const page = await context.newPage(), errors=[], requests=[];
    page.on('pageerror',e=>errors.push(e.message));
    page.on('request',r=>{if (/^https?:/.test(r.url())) requests.push(r.url());});
    const ready = () => page.waitForFunction(()=>document.querySelector('#crop').complete && document.querySelector('#crop').naturalWidth>0);
    async function choose(i) {await page.locator('#list button').nth(i).click(); await ready();}
    async function check() {for (const id of checks) await page.locator('#'+id).check();}
    async function approve(i) {await choose(i); await check(); await page.locator('#approve').click();}
    async function save(name) {
      const event=page.waitForEvent('download'); await page.locator('#save').click();
      const file=path.join(root,name+'.json'); await (await event).saveAs(file);
      return JSON.parse(fs.readFileSync(file,'utf8'));
    }
    async function resume(name, success) {
      await page.locator('#resume').setInputFiles(path.join(root,name+'.json'));
      await page.waitForFunction(text=>document.querySelector('#message').textContent.includes(text),success ? '已恢复' : '无法恢复');
    }
    const state = value => value.items.map(r=>r.decision);
    await page.goto(pathToFileURL(path.join(root,'workspace/review.html')).href); await ready();
    console.log('Browser loaded the synthetic comparison batch.');
    assert.match(await page.locator('#progress').innerText(),/待复核 5/);
    assert.deepEqual(await page.locator('#compareChoice option').evaluateAll(es=>es.map(e=>e.value)),['1','3']);
    assert.equal(await page.locator('.marker.current').count(),1);
    assert.equal(await page.locator('.marker.compared').count(),1);
    // A and C share a bridge, but are not direct peers. Equal coordinates on page 2 are not peers.
    assert.equal(await page.locator('#replaceApproval').isVisible(),false);
    await page.locator('#compareChoice').selectOption('1');
    await page.waitForFunction(()=>document.querySelector('#compareCrop').complete && document.querySelector('#compareCrop').naturalWidth===350);
    for (const scale of ['1','2']) {
      await page.locator('#zoom').selectOption(scale);
      assert.equal(await page.locator('#crop').evaluate(e=>e.getBoundingClientRect().width),370*Number(scale));
      assert.equal(await page.locator('#compareCrop').evaluate(e=>e.getBoundingClientRect().width),350*Number(scale));
    }
    await page.locator('#zoom').selectOption('fit');
    await page.screenshot({path:path.join(root,'comparison-light.png'),fullPage:true});
    await page.emulateMedia({colorScheme:'dark'});
    await page.screenshot({path:path.join(root,'comparison-dark.png'),fullPage:true});
    await page.setViewportSize({width:800,height:1000});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.equal(await page.locator('.panes').evaluate(e=>getComputedStyle(e).gridTemplateColumns.split(' ').length),1);
    await page.screenshot({path:path.join(root,'comparison-narrow.png')});
    await page.locator('#compareChoice').scrollIntoViewIfNeeded();
    await page.locator('#compareChoice').selectOption('3');
    await page.locator('#compareChoice').selectOption('1');
    await page.waitForFunction(()=>document.querySelector('#compareCrop').complete && document.querySelector('#compareCrop').naturalWidth===350);
    await page.screenshot({path:path.join(root,'comparison-narrow-alternative.png')});
    await page.setViewportSize({width:1600,height:1000});
    await page.evaluate(()=>window.scrollTo(0,0));
    await check(); assert.equal(await page.locator('#status').innerText(),'待复核');
    await approve(0); await approve(2); await approve(4);
    assert.equal(await page.locator('#comparison').isVisible(),false);
    assert.equal(await page.locator('.marker.compared').count(),0);
    await choose(1); assert.equal(await page.locator('#replaceApproval').isDisabled(),true);
    await check(); await page.locator('#approve').click();
    assert.match(await page.locator('#message').innerText(),/重叠/);
    assert.equal(await page.locator('#status').innerText(),'待复核');
    await page.locator('#replaceApproval').click();
    assert.match(await page.locator('#message').innerText(),/1 张相交批准已撤销/);
    const replaced=await save('replaced');
    console.log('Single-conflict replacement saved.');
    assert.deepEqual(state(replaced),[pending,approved,approved,pending,approved]);
    assert.deepEqual(Object.values(replaced.items[0].checks),[false,false,false]);
    // Export is the unchanged Python boundary, not an inspection of JS memory.
    assert.equal(runPython(`
import sys
from pathlib import Path
sys.path.insert(0,sys.argv[1]); import review_workspace as rw
r=Path(sys.argv[2]); result=rw.export(r/'previews',r/'replaced.json',r/'approved')
assert result==dict(approved=3,not_exported=2)
print('export: 3 nonoverlapping approvals')
`).trim(),'export: 3 nonoverlapping approvals');
    await choose(3); await check();
    assert.match(await page.locator('#replaceApproval').innerText(),/撤销 2 张/);
    await page.locator('#replaceApproval').click();
    const bridge=await save('bridge');
    console.log('Two-conflict replacement saved.');
    assert.deepEqual(state(bridge),[pending,pending,pending,approved,approved]);
    for (const i of [0,1,2]) assert.deepEqual(Object.values(bridge.items[i].checks),[false,false,false]);
    await page.locator('#compareChoice').selectOption('1');
    await page.locator('#openComparison').click(); await ready();
    assert.match(await page.locator('#name').innerText(),/b-inner/);
    assert.equal(await page.locator('#status').innerText(),'待复核');
    await page.locator('#left').fill('51'); await page.locator('#setEdges').click();
    assert.equal(await page.locator('#approve').isDisabled(),true);
    assert.equal(await page.locator('#replaceApproval').isDisabled(),true);
    for (const id of checks) assert.equal(await page.locator('#'+id).isDisabled(),true);
    await choose(3); await page.locator('#compareChoice').selectOption('1');
    assert.match(await page.locator('#compareStatus').innerText(),/原预览.*改框草稿/);
    await page.locator('#labels').uncheck();
    assert.equal(await page.locator('#status').innerText(),'待复核');
    await choose(1); await page.locator('#discardEdit').click();
    await page.reload(); await ready(); await resume('bridge',true);
    assert.deepEqual(state(await save('restored')),state(bridge));
    for (const [name,change] of [
      ['stale',r=>{r.items[0].review.documentSha256='c'.repeat(64);}],
      ['overlap',r=>{r.items[0].decision=approved;r.items[0].checks=Object.fromEntries(checks.map(k=>[k,true]));}],
    ]) {
      const modified=structuredClone(bridge); change(modified);
      fs.writeFileSync(path.join(root,name+'.json'),JSON.stringify(modified));
      await resume(name,false);
      assert.deepEqual(await save(name+'-unchanged'),bridge);
    }
    assert.deepEqual(errors,[]); assert.deepEqual(requests,[]);
    console.log('PASS: direct peers only; no cross-page/transitive folding; native/2x comparison; light/dark/800px.');
    console.log('PASS: three checks + explicit replacement; one/two direct approvals revoked with checks cleared; independent approvals retained.');
    console.log('PASS: draft cannot approve; original preview labelled; unchecking revokes; save/resume; stale/overlap restore leaves decisions unchanged.');
    console.log('PASS: Python export revalidates 3 nonoverlapping approvals; 0 browser errors; 0 HTTP requests in offline context.');
  } finally {await browser.close();}
}
main().catch(error=>{console.error(error);process.exitCode=1;}).finally(()=>{
  if (!options['--out']) fs.rmSync(root,{recursive:true,force:true});
});
