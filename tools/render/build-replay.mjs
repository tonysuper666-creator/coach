// Read existing staged corpus only. Never reads DeepTutor or starts AI.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { renderTeX } from '../mathsvc/src/render.mjs';
import { tokenize } from '../mathsvc/delims/reference.ts';
const input = process.env.COACH_CORPUS || path.join(os.homedir(), 'Coach/corpus/math');
const output = process.env.COACH_RENDER_CORPUS || path.join(os.homedir(), 'Coach/corpus/render');
const read = n => JSON.parse(fs.readFileSync(path.join(input, n + '.json'), 'utf8')).items;
const courses = read('course-formulas');
const answers = read('tutor-answers');
const formulas = courses.map(x => ({ tex: x.tex, display: x.display }));
for (const answer of answers) for (const s of tokenize(answer.content, {source: 'model'}).segments) if ('math' in s) formulas.push({tex: s.math, display: s.display});
const assets = new Map();
const rows = formulas.map((f, i) => {
  const result = renderTeX(f);
  if (result.ok) assets.set(`${f.display}:${f.tex}`, {...f, ...result});
  return {id: i, ...f, ...result};
});
fs.mkdirSync(output, {recursive:true});
fs.writeFileSync(path.join(output, 'replay.json'), JSON.stringify({messages: answers.map(a=>a.content), assets:[...assets.values()]}));
fs.writeFileSync(path.join(output, 'formulas.json'), JSON.stringify(rows));
fs.writeFileSync(path.join(output, 'currency.json'), JSON.stringify(read('currency-lines').map(x=>({text:x.text, expected:x.math_segments_on_line}))));
fs.writeFileSync(path.join(output, 'provenance.json'), JSON.stringify({source:'existing staged math corpus', courses:courses.length, answers:answers.length, formulas:formulas.length, unique:assets.size, errors:rows.filter(x=>!x.ok).map(x=>({id:x.id,error:x.error})),ai_calls:0},null,2));
console.log(JSON.stringify({courses:courses.length,answers:answers.length,formulas:formulas.length,unique:assets.size,errors:rows.filter(x=>!x.ok).length,chinese:[...assets.values()].filter(x=>/[\u3400-\u9fff]/u.test(x.tex)).length}));
