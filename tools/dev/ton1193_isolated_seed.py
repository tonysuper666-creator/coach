"""Insert three synthetic questions into a validated disposable fixture only.

Run after the isolated old service has created its ledger and before the idle
observation. This is preparation, not evidence of a switch or of learning.
"""
import argparse
import http.client
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys

from . import ton1193_isolated_acceptance as acceptance
from . import ton1193_isolated_controller as isolated
from .ton1175_switch import GateError, require
from .ton1193_trust import directory_snapshot


def seed(config_path):
    config, roots, _ = isolated.fixture(config_path)
    # This also proves that the private desktop credential belongs to the
    # currently running isolated service; never emit the token.
    observed = acceptance.observe(config, roots)
    data = Path(roots['home']) / 'Coach/data'
    parent = directory_snapshot(str(data))
    path = data / 'coach.sqlite'
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        st = os.fstat(fd)
        require(stat.S_ISREG(st.st_mode) and st.st_uid == os.getuid() and st.st_nlink == 1,
                'invalid isolated ledger')

        def unchanged():
            current = os.stat(path, follow_symlinks=False)
            require(directory_snapshot(str(data)) == parent
                    and (current.st_dev, current.st_ino) == (st.st_dev, st.st_ino),
                    'isolated ledger replaced')

        db = sqlite3.connect(path.as_uri() + '?mode=rw', uri=True, timeout=1)
        try:
            unchanged()
            db.execute('BEGIN IMMEDIATE')
            acceptance.ledger_identity(db, observed)
            for i in range(3):
                question = f'q{i}'
                options = [{'optionId': f'{question}-{j}', 'text': f'Synthetic option {i}-{j}'}
                           for j in range(4)]
                db.execute('INSERT INTO questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) '
                           'VALUES(?,?,?,?,?,?,?,?)',
                           (question, 'course-1', 'SINGLE_CHOICE', f'Synthetic question {i}',
                            json.dumps(options), 'COACH', f'synthetic-fingerprint-{i}', 'VERIFIED'))
                db.execute('INSERT INTO answer_keys(question_id,key_json) VALUES(?,?)',
                           (question, json.dumps({'correctOptionId': question + '-0'})))
                db.execute('INSERT INTO question_kp(question_id,kp_id,is_primary) VALUES(?,?,1)',
                           (question, 'kp-1'))
            unchanged()
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()
    finally:
        os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    args = parser.parse_args()
    try:
        seed(args.config)
        print('three isolated synthetic questions initialized')
        return 0
    except (GateError, OSError, ValueError, KeyError, TypeError, sqlite3.Error, http.client.HTTPException):
        print('isolated question initialization refused', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
