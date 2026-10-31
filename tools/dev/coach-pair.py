#!/usr/bin/env python3
"""Show a fresh pairing code using the installed, persistent Coach server."""
import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys


def main():
    home = Path.home()
    try:
        subprocess.run([sys.executable, str(home / 'Coach/service/control.py'), 'start'], check=True, stdout=subprocess.DEVNULL)
        install = json.loads((home / 'Coach/service/installation.json').read_text())
        with (home / 'Library/LaunchAgents/local.coach.server.plist').open('rb') as source:
            config = plistlib.load(source)
        env = dict(os.environ, **config['EnvironmentVariables'])
        print('请在手机 Coach 里输入下面的六位配对码。五分钟内有效，用过就失效。', flush=True)
        subprocess.run([str(Path(install['root']) / 'app/bin/app'), 'pair-code'], env=env, check=True)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError):
        print('学习服务暂时没有连上。先用 DeepTutor；稍后重新打开配对窗口。', flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
