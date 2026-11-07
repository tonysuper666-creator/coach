"""Static, stage-bound proof for retaining usage observations across releases.

No database is opened. Only the two literal initialization statements in each
revision's tracked UsageObservation source authorize the category-6 inventory.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

from .ton1175_switch import digest, require
from .ton1193_transaction import _read_file
from .ton1193_trust import directory_snapshot

SOURCE = 'server/app/src/main/kotlin/com/coach/server/UsageObservation.kt'


def extract(source):
    lines = source.decode('utf-8').splitlines()
    journal = [line.strip() for line in lines if 'PRAGMA journal_mode' in line]
    ddl = [line.strip() for line in lines if re.search(r'CREATE TABLE.*\busage\s*\(', line)]
    if len(journal) != 1 or len(ddl) != 1:
        return None
    statements = journal + ddl
    if not all(re.fullmatch(r's\.execute\("[^"\\]+"\)', line) for line in statements):
        return None
    return statements


def attest(old_root, new_root, old_head, new_head):
    sides = []
    for root, head in ((old_root, old_head), (new_root, new_head)):
        try:
            raw = (Path(root) / SOURCE).read_bytes()
        except FileNotFoundError:
            raw = None
        sides.append(dict(head=head, sourceSha256=hashlib.sha256(raw).hexdigest() if raw is not None else None,
                          statements=extract(raw) if raw is not None else None))
    return dict(version=1, source=SOURCE, old=sides[0], new=sides[1],
                equal=sides[0]['statements'] is not None and sides[0]['statements'] == sides[1]['statements'])


def attest_revisions(repo, old_head, new_head):
    """Read both sources from named Git commits, never from an installed release."""
    for head in (old_head, new_head):
        require(isinstance(head, str) and re.fullmatch(r'[0-9a-f]{40}', head),
                'full source commit required')
        result = subprocess.run(['git', '-C', str(repo), 'cat-file', '-e', head + '^{commit}'],
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, timeout=60)
        require(result.returncode == 0, 'source commit missing')
    with tempfile.TemporaryDirectory(prefix='ton1175-schema-') as temp:
        roots = [Path(temp) / 'old', Path(temp) / 'new']
        for root, head in zip(roots, (old_head, new_head)):
            spec = head + ':' + SOURCE
            exists = subprocess.run(['git', '-C', str(repo), 'cat-file', '-e', spec],
                                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, timeout=60)
            if exists.returncode == 0:
                source = subprocess.run(['git', '-C', str(repo), 'show', spec],
                                        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, timeout=60, check=True).stdout
                path = root / SOURCE
                path.parent.mkdir(parents=True)
                path.write_bytes(source)
        return attest(*roots, old_head, new_head)


def enabled(proof):
    return (isinstance(proof, dict) and proof.get('version') == 1 and proof.get('source') == SOURCE
            and proof.get('equal') is True and proof.get('old', {}).get('statements') is not None
            and proof['old']['statements'] == proof.get('new', {}).get('statements'))


def bound(config):
    if 'adapterManifest' not in config:
        return None  # Existing fixtures and stages without a proof stay category 5.
    path = Path(config['adapterManifest'])
    raw, _ = _read_file(path, directory_snapshot(path.parent), 0o600)
    require(digest(raw) == config['adapterManifestSha256'], 'usage schema manifest changed')
    proof = json.loads(raw).get('usageObservationSchema')
    if proof is not None:
        require(proof['old']['head'] == config['expectedSourceHead']
                and proof['new']['head'] == config['candidateHead'], 'usage schema revisions differ')
    return proof
