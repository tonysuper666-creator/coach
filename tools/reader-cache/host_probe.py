"""Check existing probe activation rights without sudo, target attachment or traffic."""
import os
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys


def preflight():
    report = dict(platform=sys.platform, euid=os.geteuid() if hasattr(os, 'geteuid') else None,
                  privilegeChanges=False, targetAttached=False, trafficCollected=False,
                  netsimdFdNonceCollectorAvailable=False, activationVerified=False)
    if sys.platform != 'darwin':
        return dict(report, status='unsupported_platform', missing='Mac probe availability not checked on Windows')
    executable = shutil.which('dtrace')
    if not executable:
        return dict(report, status='executable_missing', missing='existing dtrace executable')
    # Activate providers, then exit in BEGIN. The predicate cannot match a
    # user process. No SDK process, payload, FD or account data is observed.
    program = '\n'.join('syscall::' + name + ':return /pid == 0/ { }'
                        for name in ('read', 'write', 'recvfrom', 'sendto'))
    program += '\nBEGIN { printf("reader-boundary-ready\\n"); exit(0); }'
    command = [executable, '-q', '-n', program]
    report['command'] = command
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=5)
        report.update(exit=result.returncode, stdout=result.stdout, stderr=result.stderr)
        report['activationVerified'] = result.returncode == 0 and result.stdout.strip() == 'reader-boundary-ready'
        report['status'] = 'activation_verified' if report['activationVerified'] else 'activation_rejected'
    except subprocess.TimeoutExpired:
        report.update(status='activation_timeout', exit=None)
    except OSError as error:
        report.update(status='activation_failed', error=type(error).__name__)
    report['missing'] = ('Activation is not an FD/nonce or timestamp-dispatch collector. '
                         'The explicit single relay is an alternative boundary, not netsimd instrumentation.')
    return report


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True,help='Fresh JSON receipt; no permission changes')
    args=parser.parse_args()
    report=preflight()
    with args.output.open('x',encoding='utf-8') as output:
        json.dump(report,output,indent=2)
    print(json.dumps(report))
    raise SystemExit(0 if report['activationVerified'] else 1)
