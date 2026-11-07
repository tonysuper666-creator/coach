"""Fail acceptance after proving the disposable candidate is really running."""
import argparse
import errno
import http.client
import json
from pathlib import Path
import sys
import time

from . import ton1193_isolated_acceptance as acceptance
from . import ton1193_isolated_controller as isolated
from . import ton1193_live as live
from .ton1175_switch import GateError, atomic, require


def verify(config_path, baseline, started, deadline):
    config, roots, _ = isolated.fixture(config_path)
    diagnostic = Path(config['stateDir']).parent / 'injected-diagnostic.json'
    step = 'PHASE'
    try:
        require(baseline == str(Path(config['stateDir']) / 'acceptance-baseline.json')
                and deadline - started == 300 and started <= time.time() < deadline
                and live.live_acceptance(config['stateDir']) == 'validating',
                'isolated acceptance phase or deadline mismatch')
        step = 'HELLO'
        observed = acceptance.observe_when_ready(config, roots, deadline)
        require(time.time() < deadline and live.live_acceptance(config['stateDir']) == 'validating',
                'isolated acceptance transaction ended')
        step = 'EVIDENCE_WRITE'
        atomic(Path(config['stateDir']).parent / 'injected-acceptance.json',
               json.dumps(observed).encode())
        return True
    except (GateError, OSError, ValueError, KeyError, TypeError, http.client.HTTPException) as exc:
        reason = ('CONNECTION_REFUSED' if isinstance(exc, OSError) and exc.errno == errno.ECONNREFUSED
                  else 'TIMEOUT' if isinstance(exc, TimeoutError)
                  else 'GATE' if isinstance(exc, GateError) else 'OTHER')
        atomic(diagnostic, json.dumps({'step': step, 'reason': reason}).encode())
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('baseline')
    parser.add_argument('started', type=float)
    parser.add_argument('deadline', type=float)
    args = parser.parse_args()
    try:
        if verify(args.config, args.baseline, args.started, args.deadline):
            print('deliberate isolated acceptance failure after candidate authenticated', file=sys.stderr)
        else:
            print('isolated injected verifier refused', file=sys.stderr)
    except (GateError, OSError, ValueError, KeyError, TypeError):
        print('isolated injected verifier refused', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
