#!/usr/bin/env python3
"""Capture the installed alpha on an existing emulator; outputs stay outside Git.

Run once per visible journey step. This records real pixels, frame data and
JankStats log lines; it does not infer that a screenshot proves an interaction.
"""
import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--matrix', action='store_true', help='Outer/inner screen, light/dark')
    parser.add_argument('--scroll', action='store_true', help='Measure three down/up scroll pairs')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.label):
        parser.error('label must be a short filename-safe identifier')
    out = args.out.expanduser().resolve()
    if any((p / '.git').exists() for p in [out, *out.parents]):
        parser.error('screenshots and logs must be outside a repository')
    out.mkdir(parents=True, exist_ok=True)
    prefix = ['adb', '-s', args.serial]

    def adb(*command, binary=False):
        return subprocess.check_output([*prefix, *map(str, command)], text=not binary, timeout=30)

    if adb('get-state').strip() != 'device':
        raise RuntimeError('Emulator is unavailable')
    original_size = adb('shell', 'wm', 'size')
    original_night = adb('shell', 'cmd', 'uimode', 'night')
    override = re.search(r'Override size: (\d+x\d+)', original_size)
    night = re.search(r'Night mode: (yes|no|auto)', original_night)
    rows = []
    try:
        variants = [(None, None)] if not args.matrix else [
            (size, theme) for size in ['1248x1972', '1848x2448'] for theme in ['no', 'yes']]
        for size, theme in variants:
            if size:
                adb('shell', 'wm', 'size', size)
                adb('shell', 'cmd', 'uimode', 'night', theme)
            time.sleep(2)
            tag = args.label + (f'-{size}-' + ('dark' if theme == 'yes' else 'light') if size else '')
            if args.scroll:
                adb('shell', 'dumpsys', 'gfxinfo', 'com.coach.android', 'reset')
                bounds = size or re.findall(r'\d+x\d+', original_size)[-1]
                width, height = map(int, bounds.split('x'))
                for _ in range(3):
                    adb('shell', 'input', 'swipe', width//2, height*3//4, width//2, height//3, 500)
                    adb('shell', 'input', 'swipe', width//2, height//3, width//2, height*3//4, 500)
            pixels = adb('exec-out', 'screencap', '-p', binary=True)
            if not pixels.startswith(b'\x89PNG\r\n\x1a\n'):
                raise RuntimeError('Screen capture was not a PNG')
            image = out / (tag + '.png')
            image.write_bytes(pixels)
            frames = adb('shell', 'dumpsys', 'gfxinfo', 'com.coach.android', 'framestats')
            (out / (tag + '-frames.txt')).write_text(frames)
            logs = adb('logcat', '-d', '-s', 'CoachStudy:I', 'CoachTtft:I', 'CoachJank:I', 'CoachRender:I', '*:S')
            metrics = [line for line in logs.splitlines() if re.search(r'JANK|TTFT|first.?word|first.?token', line, re.I)]
            (out / (tag + '-metrics.txt')).write_text('\n'.join(metrics) + '\n')
            rows.append({'label': tag, 'at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                         'screenshot': image.name, 'sha256': hashlib.sha256(pixels).hexdigest(),
                         'jankstats_log_lines': len([line for line in metrics if 'JANK' in line.upper()])})
    finally:
        if args.matrix:
            adb('shell', 'wm', 'size', override.group(1) if override else 'reset')
            if night:
                adb('shell', 'cmd', 'uimode', 'night', night.group(1))
        (out / (args.label + '-captures.json')).write_text(json.dumps(rows, indent=2) + '\n')
    print(json.dumps({'captures': len(rows), 'label': args.label}))


if __name__ == '__main__':
    main()
