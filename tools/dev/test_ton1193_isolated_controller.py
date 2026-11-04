import json
import os
from pathlib import Path
import shutil
import unittest
import uuid

from tools.dev import ton1193_isolated_controller as isolated
from tools.dev.ton1175_switch import GateError, atomic


class IsolatedControllerTest(unittest.TestCase):
    def setUp(self):
        self.root = Path('/tmp').resolve() / ('ton1193-' + uuid.uuid4().hex)
        self.root.mkdir(mode=0o700)
        self.addCleanup(shutil.rmtree, self.root)
        home = self.root / 'home'
        self.path = self.root / 'controller.json'
        self.config = {'plist': str(home / 'Library/LaunchAgents/service.plist'),
                       'installation': str(home / 'Coach/service/installation.json'),
                       'stateDir': str(self.root / 'stage'),
                       'adapterSnapshotDir': str(self.root / 'stage/snapshot'),
                       'adapterManifest': str(self.root / 'manifest.json'),
                       'release': str(home / 'Coach/service/releases/candidate'),
                       'adapterEnvPins': {'COACH_TAIL_HOST': '', 'COACH_MANAGE_SERVICES': '1',
                                          'COACH_PORT': '23101', 'BRIDGE_PORT': '23102'}}

    def check(self):
        atomic(self.path, json.dumps(self.config).encode())
        return isolated.fixture(str(self.path))

    def test_fixture_derives_random_label_and_roots(self):
        config, roots, label = self.check()
        self.assertEqual(config, self.config)
        self.assertEqual(roots['home'], str(self.root / 'home'))
        self.assertEqual(label, 'local.coach.ton1193.test.' + self.root.name.removeprefix('ton1193-'))

    def test_production_pointer_and_outside_release_are_rejected(self):
        self.config['plist'] = str(Path.home() / 'Library/LaunchAgents/local.coach.server.plist')
        with self.assertRaisesRegex(GateError, 'fixture path mismatch'):
            self.check()
        self.config['plist'] = str(self.root / 'home/Library/LaunchAgents/service.plist')
        self.config['release'] = str(Path.home() / 'Coach/service/releases/candidate')
        with self.assertRaisesRegex(GateError, 'release outside root'):
            self.check()

    def test_reserved_or_equal_ports_and_nonlocal_tail_are_rejected(self):
        for key, value in (('COACH_PORT', '8790'), ('BRIDGE_PORT', '23101'),
                           ('COACH_TAIL_HOST', 'example.com')):
            with self.subTest(key=key):
                old = self.config['adapterEnvPins'][key]
                self.config['adapterEnvPins'][key] = value
                with self.assertRaises(GateError):
                    self.check()
                self.config['adapterEnvPins'][key] = old

    def test_private_root_and_config_are_required(self):
        self.check()
        os.chmod(self.root, 0o755)
        with self.assertRaisesRegex(GateError, 'fixture root must be private'):
            isolated.fixture(str(self.path))
        os.chmod(self.root, 0o700)
        self.path.unlink()
        self.path.symlink_to('/etc/passwd')
        with self.assertRaises(GateError):
            isolated.fixture(str(self.path))


if __name__ == '__main__':
    unittest.main()
