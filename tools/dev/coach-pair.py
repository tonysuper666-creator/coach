#!/usr/bin/env python3
"""Show a fresh pairing code using the installed, persistent Coach server."""
from pathlib import Path
import subprocess
import sys


def main():
    home = Path.home()
    try:
        # The durable controller owns readiness and the pairing write under
        # one operation lock; this desktop entry must never invoke the JVM.
        subprocess.run([sys.executable, str(home / 'Coach/service/control.py'), 'pair-code'], check=True)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError):
        print('学习服务暂时没有连上。先用 DeepTutor；稍后重新打开配对窗口。', flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
