import importlib.util
import json
from pathlib import Path
import plistlib
import sys
import unittest
from unittest import mock

from tools.dev import friend_service_guard as guard
from tools.dev import test_friend_service_guard as fixtures


class PairOperationTest(unittest.TestCase):
    def setUp(self):
        fixtures.FriendStartTest.setUp(self)
        actual = self.root / 'coach.sqlite'
        self.db.path.rename(actual)
        self.db.path = actual
        self.db.binding['database'] = str(actual)
        self.write_policy()
    write_policy = fixtures.FriendStartTest.write_policy
    locked = fixtures.FriendStartTest.locked
    stop = fixtures.FriendStartTest.stop
    launch = fixtures.FriendStartTest.launch

    def test_pair_write_keeps_lock_for_capable_and_legacy(self):
        for head in ('a' * 40, 'b' * 40):
            with self.subTest(head=head):
                def pair():
                    self.locked()
                    with self.assertRaises(BlockingIOError):
                        guard.start(self.path, head, self.db.path, self.stop, self.launch)
                    self.calls.append('pair')
                guard.start(self.path, head, self.db.path, self.stop, self.launch, after_ready=pair)
                self.assertEqual(self.calls[-2:], ['start', 'pair'])

    def test_blocked_or_busy_operation_never_issues_pair_code(self):
        pair = mock.Mock()
        with guard.operation(self.path):
            with self.assertRaises(BlockingIOError):
                guard.start(self.path, 'a' * 40, self.db.path, self.stop, self.launch, pair)
        self.blocked.write_text('{}')
        with self.assertRaises(guard.GateError):
            guard.start(self.path, 'a' * 40, self.db.path, self.stop, self.launch, pair)
        pair.assert_not_called()

    def test_pair_failure_does_not_stop_ready_owner_or_leave_lock(self):
        with self.assertRaises(RuntimeError):
            guard.start(self.path, 'b' * 40, self.db.path, self.stop, self.launch,
                        after_ready=mock.Mock(side_effect=RuntimeError('pair failed')))
        self.assertEqual(self.calls, ['close', 'stop', 'start'])
        self.assertFalse(self.blocked.exists())
        with guard.operation(self.path):
            pass

    def controller(self):
        spec = importlib.util.spec_from_file_location('pair_controller', Path(guard.__file__).with_name('coach-service.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_controller_uses_bound_pointers_and_signs_inside_guard(self):
        controller = self.controller()
        record = self.root / 'installation.json'
        record.write_text(json.dumps({'sourceHead': 'a' * 40, 'dataDir': str(self.root), 'root': str(self.root / 'release')}))
        plist = self.root / 'service.plist'
        plist.write_bytes(plistlib.dumps({'EnvironmentVariables': {'HOME': str(self.root)}}))
        def run(*args, **kwargs):
            if args[-1] == 'pair-code':
                self.locked()
                self.assertEqual(args[0], self.root / 'release/app/bin/app')
                self.assertEqual(kwargs['env']['HOME'], str(self.root))
        with mock.patch.multiple(controller, BASE=self.root, RECORD=record, PLIST=plist), \
                mock.patch.dict(sys.modules, friend_service_guard=guard), \
                mock.patch.object(controller, 'run', side_effect=run) as invoke, \
                mock.patch.object(controller, 'loaded', return_value=True), \
                mock.patch.object(controller, 'ready'):
            controller.pair_code()
            self.assertEqual(invoke.call_args.args[-1], 'pair-code')

    def test_pointer_drift_after_readiness_prevents_pairing(self):
        controller = self.controller()
        record = self.root / 'installation.json'
        record.write_text(json.dumps({'sourceHead': 'a' * 40, 'dataDir': str(self.root)}))
        plist = self.root / 'service.plist'
        plist.write_bytes(plistlib.dumps({'EnvironmentVariables': {}}))
        pair = mock.Mock()
        with mock.patch.multiple(controller, BASE=self.root, RECORD=record, PLIST=plist), \
                mock.patch.dict(sys.modules, friend_service_guard=guard), \
                mock.patch.object(controller, 'run'), \
                mock.patch.object(controller, 'loaded', return_value=True), \
                mock.patch.object(controller, 'ready', side_effect=lambda _: record.write_text('{}')):
            with self.assertRaises(RuntimeError):
                controller.start(after_ready=pair)
        pair.assert_not_called()

    def test_desktop_entry_only_delegates_pairing_to_controller(self):
        spec = importlib.util.spec_from_file_location('pair_desktop', Path(guard.__file__).with_name('coach-pair.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with mock.patch.object(module.Path, 'home', return_value=self.root), \
                mock.patch.object(module.subprocess, 'run') as invoke:
            module.main()
        invoke.assert_called_once_with([sys.executable, str(self.root / 'Coach/service/control.py'),
                                        'pair-code'], check=True)


if __name__ == '__main__':
    unittest.main()
