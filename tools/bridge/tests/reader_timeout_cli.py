#!/usr/bin/env python3
"""Synthetic-only CLI for the real reader timeout/cleanup replay; never reads private material."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

base = Path(os.environ['READER_STUB_DIR'])
if sys.argv[1:3] == ['auth', 'status']:
    print(json.dumps(dict(loggedIn=True, authMethod='claude.ai', subscriptionType='max')))
    sys.exit(0)
sys.stdin.buffer.read()  # Intentionally discard the synthetic request; never persist it.
mode = (base / 'mode').read_text().strip()
child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(300)'],
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
with (base / 'pids').open('w') as out:
    json.dump([os.getpid(), child.pid], out)
start = time.monotonic()

def event(obj):
    print(json.dumps(obj, ensure_ascii=False), flush=True)

def delta(text, body=False):
    event(dict(type='stream_event', event=dict(type='content_block_delta', index=0,
        delta=dict(type='text_delta', text=text))))
    if body:
        with (base / 'times').open('a') as out:
            out.write(f'{time.monotonic()-start:.6f}\n')

model = 'claude-sonnet-5-5'
event(dict(type='system', subtype='init', model=model, session_id='synthetic'))
full = ''
try:
    if mode == 'first':
        delta('{"paragraphs":[{"anchors":["S1')
        while True:
            delta(' '); time.sleep(.2)  # JSON structure/anchor output must not renew first-content time.
    delta('{"paragraphs":[{"text":"')
    if mode in ('gap', 'cancel', 'disconnect'):
        delta('合', True)
        delta('","anchors":["S1')
        while True:
            delta(' '); time.sleep(.2)
    count, interval = (121, 1) if mode == 'long' else (200, .2)
    for i in range(count):
        if i:
            time.sleep(interval)
        delta('合', True)
    delta('","anchors":["S1"]}]}')
    full = json.dumps(dict(paragraphs=[dict(text='合'*count, anchors=['S1'])]), ensure_ascii=False)
    event(dict(type='result', subtype='success', is_error=False, result=full,
        terminal_reason='success', stop_reason='end_turn', modelUsage={model:dict(outputTokens=count)},
        usage=dict(input_tokens=10, output_tokens=count)))
finally:
    child.terminate()
    child.wait(timeout=5)
