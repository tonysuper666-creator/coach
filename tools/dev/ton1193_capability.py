"""Read-only protocol inspection; this is not a production switch adapter."""
import json
import os
from pathlib import Path
import pwd
import stat
import sys

from .ton1175_switch import GateError, require
from .ton1193_trust import _components, _open_path, capability


def inspect_installation(checkout, home):
    """Inspect the fixed installation location without following control links."""
    home = Path(home)
    installation = home / 'Coach/service/installation.json'
    fd = _open_path(str(installation))
    try:
        node = os.fstat(fd)
        require(stat.S_ISREG(node.st_mode) and node.st_uid == os.getuid()
                and not node.st_mode & 0o022, 'unsafe installation record')
        with os.fdopen(fd, 'rb') as source:
            fd = -1
            data = source.read(1024 * 1024 + 1)
        require(len(data) <= 1024 * 1024, 'installation record too large')
        record = json.loads(data)
        require(isinstance(record, dict) and isinstance(record.get('root'), str),
                'invalid installation record')
        root = record['root']
        _components(root)
        require(Path(root).parent == home / 'Coach/service/releases', 'path-escape')
        return capability(checkout, root, record.get('sourceHead'))
    finally:
        if fd >= 0:
            os.close(fd)


def main():
    if sys.argv[1:] != ['capability']:
        print('usage: python3 -m tools.dev.ton1193_capability capability', file=sys.stderr)
        return 64
    try:
        result = inspect_installation(Path(__file__).resolve().parents[2],
                                      pwd.getpwuid(os.getuid()).pw_dir)
        print(json.dumps(result))
        return 0
    except (GateError, OSError, ValueError, KeyError) as exc:
        print('capability refused: ' + str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
