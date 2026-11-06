import base64
import json
import os
from pathlib import Path
import shutil
import socket
import stat
import unittest
from unittest import mock

from tools.dev import ton1193_isolated_controller as isolated
from tools.dev.ton1175_switch import GateError, atomic


class IsolatedControllerTest(unittest.TestCase):
    def setUp(self):
        self.root = isolated.new_root()
        self.addCleanup(shutil.rmtree, self.root)
        home = self.root / 'h'
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
        self.assertEqual(roots['home'], str(self.root / 'h'))
        self.assertEqual(label, 'local.coach.ton1193.test.' + isolated.fixture_nonce(self.root))

    def test_production_pointer_and_outside_release_are_rejected(self):
        self.config['plist'] = str(Path.home() / 'Library/LaunchAgents/local.coach.server.plist')
        with self.assertRaisesRegex(GateError, 'fixture path mismatch'):
            self.check()
        self.config['plist'] = str(self.root / 'h/Library/LaunchAgents/service.plist')
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
        self.path.chmod(0o644)
        with self.assertRaises(GateError):
            isolated.fixture(str(self.path))
        self.path.chmod(0o600)
        os.chmod(self.root, 0o755)
        with self.assertRaisesRegex(GateError, 'fixture root must be private'):
            isolated.fixture(str(self.path))
        os.chmod(self.root, 0o700)
        self.path.unlink()
        self.path.symlink_to('/etc/passwd')
        with self.assertRaises(GateError):
            isolated.fixture(str(self.path))

    def test_full_nonce_roundtrip_and_noncanonical_names_are_rejected(self):
        nonce = bytes(range(16))
        token = base64.urlsafe_b64encode(nonce).decode().rstrip('=')
        parent = Path('/tmp').resolve()
        self.assertEqual(isolated.fixture_nonce(parent / ('t' + token)), nonce.hex())
        for bad in ('t' + token[:-1] + 'x', 't' + token[:-1] + '!',
                    't' + token[:-1], 't' + token + '=', 'ton1193-' + nonce.hex()):
            with self.subTest(name=bad), self.assertRaises(GateError):
                isolated.fixture_nonce(parent / bad)
        for raw in (str(parent) + '/./t' + token, str(parent) + '/other/../t' + token,
                    't' + token, str(parent / ('t' + token)) + '/'):
            with self.subTest(path=raw), self.assertRaises(GateError):
                isolated.fixture_nonce(raw)

    def test_collision_retries_without_reusing_or_removing_existing_root(self):
        occupied_nonce = bytes.fromhex(isolated.fixture_nonce(self.root))
        fresh_nonce = bytes(reversed(occupied_nonce))
        with mock.patch.object(isolated.secrets, 'token_bytes', side_effect=[occupied_nonce, fresh_nonce]) as random:
            second = isolated.new_root()
        self.addCleanup(shutil.rmtree, second)
        self.assertEqual(random.call_args_list, [mock.call(16), mock.call(16)])
        self.assertNotEqual(second, self.root)
        self.assertTrue(self.root.is_dir())

    def test_alias_and_traversal_never_reach_launchctl(self):
        self.check()
        alias = isolated.new_root()
        alias.rmdir()
        alias.symlink_to(self.root, target_is_directory=True)
        self.addCleanup(alias.unlink)
        with mock.patch.object(isolated.controller, '_launchctl') as launch:
            for bad in (str(alias / 'controller.json'), str(self.root / '../controller.json'),
                        str(self.root) + '/./controller.json'):
                with self.subTest(path=bad), self.assertRaises((GateError, OSError)):
                    isolated.run(bad, 'start', True)
            launch.assert_not_called()

    def test_private_ancestors_and_byte_budget(self):
        target = self.root / 'h/Coach/state'
        isolated.private_directories(target, self.root)
        for path in (self.root, *target.parents[:3], target):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700)
        path = isolated.activity_socket(self.root / 'h')
        # macOS's physical /private/tmp is seven bytes longer than /tmp.
        self.assertEqual(len(os.fsencode(path)), len(os.fsencode(Path('/tmp').resolve())) + 89)
        legacy = Path('/tmp').resolve() / ('ton1193-' + 'a' * 32) / 'home'
        with self.assertRaisesRegex(GateError, 'byte budget'):
            isolated.activity_socket(legacy)
        with mock.patch.object(isolated.controller, '_launchctl') as launch:
            with self.assertRaises(GateError):
                isolated.run(legacy.parent / 'controller.json', 'start', True)
            launch.assert_not_called()
        multibyte = '/tmp/' + 'é' * 22
        self.assertLess(len(multibyte + '/Coach/state/activity-' + '0' * 36 + '.sock'), 101)
        with self.assertRaisesRegex(GateError, 'byte budget'):
            isolated.activity_socket(multibyte)
        with self.assertRaisesRegex(GateError, 'ASCII'):
            isolated.activity_socket('/tmp/é')
        target.chmod(0o755)
        with self.assertRaisesRegex(GateError, 'private'):
            isolated.private_directories(target, self.root)

    def test_two_live_fixtures_do_not_share_endpoint_or_identity(self):
        second = isolated.new_root()
        self.addCleanup(shutil.rmtree, second)
        paths = [isolated.activity_socket(root / 'h') for root in (self.root, second)]
        self.assertNotEqual(isolated.fixture_nonce(self.root), isolated.fixture_nonce(second))
        listeners = []
        try:
            for root, path in zip((self.root, second), paths):
                isolated.private_directories(path.parent, root)
                (root / 'h/token').write_text(isolated.fixture_nonce(root))
                listener = socket.socket(socket.AF_UNIX)
                listeners.append(listener)
                listener.bind(str(path)); listener.listen(1)
            listeners[0].close(); paths[0].unlink()
            self.assertTrue(paths[1].exists())
            with socket.socket(socket.AF_UNIX) as client:
                client.connect(str(paths[1]))
                peer, _ = listeners[1].accept()
                with peer:
                    peer.sendall(b'still-owned-by-B')
                    self.assertEqual(client.recv(64), b'still-owned-by-B')
            with socket.socket(socket.AF_UNIX) as occupied:
                with self.assertRaises(OSError):
                    occupied.bind(str(paths[1]))
            self.assertTrue(paths[1].exists())
            self.assertNotEqual((self.root / 'h/token').read_text(), (second / 'h/token').read_text())
        finally:
            for listener in listeners:
                listener.close()
            for path in paths:
                path.unlink(missing_ok=True)


if __name__ == '__main__':
    unittest.main()
