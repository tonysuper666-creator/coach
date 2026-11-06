"""Fail acceptance after proving the disposable candidate is really running."""
import argparse
import json
from pathlib import Path
import sys
import time

from . import ton1193_isolated_acceptance as acceptance
from . import ton1193_isolated_controller as isolated
from . import ton1193_live as live
from .ton1175_switch import GateError, atomic, require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('baseline')
    parser.add_argument('started', type=float)
    parser.add_argument('deadline', type=float)
    args = parser.parse_args()
    try:
        config, roots, _ = isolated.fixture(args.config)
        require(args.baseline == str(Path(config['stateDir']) / 'acceptance-baseline.json')
                and args.deadline - args.started == 300
                and args.started <= time.time() < args.deadline
                and live.live_acceptance(config['stateDir']) == 'validating',
                'isolated acceptance phase or deadline mismatch')
        observed = acceptance.observe(config, roots)
        atomic(Path(config['stateDir']).parent / 'injected-acceptance.json',
               json.dumps(observed).encode())
        print('deliberate isolated acceptance failure after candidate authenticated', file=sys.stderr)
    except (GateError, OSError, ValueError, KeyError, TypeError):
        print('isolated injected verifier refused', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
