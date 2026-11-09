import json
import os
from pathlib import Path
import shutil
import sqlite3
import unittest
from unittest import mock

from tools.dev import ton1193_isolated_seed as seed
from tools.dev import ton1193_isolated_controller as isolated
from tools.dev.ton1175_switch import GateError, atomic, digest


class SeedTest(unittest.TestCase):
    def setUp(self):
        self.root = isolated.new_root()
        self.addCleanup(shutil.rmtree, self.root)
        home = self.root / 'h'
        data = home / 'Coach/data'
        isolated.private_directories(data, self.root)
        self.path = data / 'coach.sqlite'
        self.db = sqlite3.connect(self.path)
        migration = Path(__file__).resolve().parents[2] / 'server/store/src/main/resources/migrations/V001__ledger.sql'
        self.db.executescript(migration.read_text())
        self.db.execute("INSERT INTO ledger_meta(singleton,epoch) VALUES(1,'test')")
        self.db.execute('INSERT INTO devices(device_id,token_sha256) VALUES(?,?)',
                        ('desktop', digest(b'test-token')))
        self.db.commit()
        self.addCleanup(self.db.close)
        self.config_path = self.root / 'controller.json'
        config = {'plist': str(home / 'Library/LaunchAgents/service.plist'),
                  'installation': str(home / 'Coach/service/installation.json'),
                  'stateDir': str(self.root / 'stage'),
                  'adapterSnapshotDir': str(self.root / 'stage/snapshot'),
                  'adapterManifest': str(self.root / 'manifest.json'),
                  'release': str(home / 'Coach/service/releases/new'),
                  'adapterEnvPins': {'COACH_TAIL_HOST': '127.0.0.1', 'COACH_MANAGE_SERVICES': '1',
                                     'COACH_PORT': '29103', 'BRIDGE_PORT': '29104'}}
        atomic(self.config_path, json.dumps(config).encode())
        self.observation = mock.patch.object(seed.acceptance, 'observe', return_value={
            'epoch': 'test', 'credentialSha256': digest(b'test-token')})
        self.observation.start()
        self.addCleanup(self.observation.stop)

    def test_three_questions_match_learning_driver(self):
        seed.seed(str(self.config_path))
        questions = self.db.execute('SELECT question_id,options FROM questions ORDER BY question_id').fetchall()
        self.assertEqual([q for q, _ in questions], ['q0', 'q1', 'q2'])
        for question, options in questions:
            self.assertEqual(json.loads(options)[0]['optionId'], question + '-0')
            key = self.db.execute('SELECT key_json FROM answer_keys WHERE question_id=?', (question,)).fetchone()
            self.assertEqual(json.loads(key[0])['correctOptionId'], question + '-0')
        self.assertEqual(self.db.execute('SELECT count(*) FROM question_kp').fetchone()[0], 3)

    def test_collision_rolls_back_all_three(self):
        self.db.execute("INSERT INTO answer_keys(question_id,key_json) VALUES('q1','{}')")
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            seed.seed(str(self.config_path))
        self.assertEqual(self.db.execute('SELECT count(*) FROM questions').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT count(*) FROM question_kp').fetchone()[0], 0)

    def test_hardlink_or_changed_identity_refused(self):
        link = self.path.parent / 'other.sqlite'
        os.link(self.path, link)
        with self.assertRaisesRegex(GateError, 'invalid isolated ledger'):
            seed.seed(str(self.config_path))
        link.unlink()
        self.db.execute("UPDATE ledger_meta SET epoch='other'")
        self.db.commit()
        with self.assertRaisesRegex(GateError, 'identity changed'):
            seed.seed(str(self.config_path))
        self.assertEqual(self.db.execute('SELECT count(*) FROM questions').fetchone()[0], 0)

    def test_non_fixture_config_refused(self):
        with self.assertRaises(GateError):
            seed.seed('/tmp/controller.json')


if __name__ == '__main__':
    unittest.main()
