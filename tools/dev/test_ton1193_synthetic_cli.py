import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest
from unittest.mock import patch

from . import ton1193_isolated_controller as isolated
from . import ton1193_synthetic_cli as synthetic
from .ton1175_switch import GateError, atomic, file_hash


class SyntheticCliTest(unittest.TestCase):
    def setUp(self):
        self.root = isolated.new_root()
        self.addCleanup(shutil.rmtree, self.root)

    def test_cli_does_not_read_stdin_or_echo_prompt_and_is_deterministic(self):
        proof = synthetic.install(self.root)
        path = Path(proof['path'])
        self.assertEqual(path.stat().st_mode & 0o777, 0o700)
        child = subprocess.Popen([sys.executable, str(path), '-p', '--output-format', 'stream-json'],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            # Keep stdin open without EOF; completion proves it does not await a prompt.
            child.wait(timeout=5)
            out, err = child.communicate(b'private-synthetic-prompt')
            self.assertEqual(child.returncode, 0)
            self.assertEqual(err, b'')
            second = subprocess.check_output([sys.executable, str(path), '-p', '--output-format', 'stream-json'])
            self.assertEqual(out, second)
            self.assertNotIn(b'private-synthetic-prompt', out)
            self.assertEqual(json.loads(out.splitlines()[-1])['modelUsage'].keys(), {synthetic.MODEL})
        finally:
            if child.poll() is None:
                child.kill()
            child.communicate()
        auth = subprocess.check_output([sys.executable, str(path), 'auth', 'status'])
        self.assertEqual(json.loads(auth), {'loggedIn': True, 'authMethod': 'SYNTHETIC'})
        self.assertEqual(subprocess.run([sys.executable, str(path), 'login'], capture_output=True).returncode, 2)

    def test_install_refuses_nonprivate_paths_and_manifest_detects_tampering(self):
        with self.assertRaises(GateError):
            synthetic.install(self.root / 'h')
        proof = synthetic.install(self.root)
        manifest = self.root / 'manifest.json'
        atomic(manifest, json.dumps({'isolatedSyntheticCli': proof}).encode())
        config = {'stateDir': str(self.root / 'stage'), 'adapterManifest': str(manifest),
                  'adapterManifestSha256': file_hash(manifest)}
        self.assertEqual(synthetic.verify(config), proof)
        Path(proof['path']).write_text('tampered')
        with self.assertRaisesRegex(GateError, 'synthetic CLI changed'):
            synthetic.verify(config)

    def test_managed_daily_logs_use_byte_offsets_and_include_rotation(self):
        config = {'stateDir': str(self.root / 'stage')}
        logs = self.root / 'h/Coach/logs'
        logs.mkdir(parents=True)
        previous = logs / 'bridge-2026-11-06.log'
        previous.write_text('旧行 rid=old served=real fallback=1 status=failed\n')
        offsets = synthetic.log_offsets(config)
        with previous.open('a') as stream:
            stream.write('new rid=one served=claude-ton1193-synthetic fallback=0 status=complete\n')
        (logs / 'bridge-2026-11-07.log').write_text(
            'new rid=two served=claude-ton1193-synthetic fallback=0 status=complete\n')
        with patch.object(synthetic, 'health', return_value={'cli': 'SYNTHETIC'}):
            result = synthetic.evidence(config, offsets)
            self.assertEqual(result['completedRequests'], 2)
            self.assertEqual(result['served_by'], synthetic.MODEL)
            self.assertFalse(result['fallback'])
