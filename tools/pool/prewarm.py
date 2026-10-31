#!/usr/bin/env python3
"""Fill each requested KP through the running server's serialized, reviewed pool worker.

No direct database writes, teaching generation, or learner answers. Operator must first
verify the authorized release and idle window when targeting production (see docs).
"""
import argparse
from datetime import datetime, timezone
import json
import subprocess
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--course', required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--token-file', type=Path, required=True)
    parser.add_argument('--kp-file', type=Path, required=True, help='JSON array of KP IDs')
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--idle-check', type=Path, help='Optional executable returning 0 only when production is idle; checked before every KP')
    args = parser.parse_args()
    ids = json.loads(args.kp_file.read_text())
    assert isinstance(ids, list) and ids and all(isinstance(kp, str) and kp for kp in ids)
    results = []
    token = args.token_file.read_text().strip()
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *unused, **kwargs):
            raise RuntimeError('Refusing to forward device credentials to a redirect')
    opener = urllib.request.build_opener(NoRedirect)
    for kp in ids:
        if args.idle_check and subprocess.run([str(args.idle_check.resolve())]).returncode != 0:
            print('Idle check did not pass; stopping before the next KP', flush=True)
            return 2
        now = datetime.now(timezone.utc)
        if datetime(2026, 11, 2, tzinfo=timezone.utc) <= now < datetime(2026, 11, 2, 15, tzinfo=timezone.utc):
            raise RuntimeError('Examination stop window: no prewarm calls permitted')
        request = urllib.request.Request(args.url.rstrip('/') + '/v1/prewarm',
            data=json.dumps(dict(courseId=args.course, kpIds=[kp], questionsOnly=True)).encode(),
            headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token,
                     'X-Coach-Device': args.device})
        try:
            with opener.open(request, timeout=1500) as response:
                result = json.load(response)['results'][0]
        except Exception as error:
            # Retain every failed sample without leaking credentials or response bodies.
            result = dict(kpId=kp, reached=False, error=type(error).__name__)
        results.append(result)
        args.report.write_text(json.dumps(results, ensure_ascii=False, indent=2))
        print(json.dumps(result, ensure_ascii=False), flush=True)
    return 0 if all(row.get('reached') and row.get('available', 0) >= 6 for row in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
