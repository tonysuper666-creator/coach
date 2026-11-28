"""Run the CLI with an isolated home; unauthorized runs must never probe a phone."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).with_name('check-phone-reconnect.py')


class PhoneAuthorizationTest(unittest.TestCase):
    def refuse(self, port, card='TON-1094', until=None):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            config = home/'Coach/config'
            config.mkdir(parents=True)
            (config/'local.properties').write_text('coach.tailHost=127.0.0.2\ncoach.sdkDir=/no-sdk\n')
            phone = home/'Coach/testphone'
            (phone/'lease.d').mkdir(parents=True)
            if card is not None:
                (phone/'lease.d/info').write_text(
                    f'card={card}\nuntil={until or int(time.time())+60}\n')
            # The real helpers and real phone must never be reached in these cases.
            for helper in ('probe.sh', 'install.sh'):
                (phone/helper).write_text('touch "$HOME/UNAUTHORIZED_PHONE_ACCESS"\nexit 99\n')
            (phone/'lease.sh').write_text('exit 0\n')
            out = home/'evidence'
            result = subprocess.run([sys.executable, str(SCRIPT), '--serial', 'not-a-device',
                '--port', str(port), '--evidence', str(out)], env=dict(os.environ, HOME=str(home)),
                capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((home/'UNAUTHORIZED_PHONE_ACCESS').exists())
            if (out/'summary.json').exists():
                self.assertEqual('failed', json.loads((out/'summary.json').read_text())['result'])
                self.assertFalse((out/'private-data').exists())

    def test_production_and_invalid_ports_refused_before_probe(self):
        for port in (8790, 8791, 18791, 0, 65536):
            with self.subTest(port=port):
                self.refuse(port)

    def test_no_grant_refused_before_probe(self):
        self.refuse(29194, card=None)

    def test_other_card_grant_refused_before_probe(self):
        self.refuse(29194, card='ANOTHER-CARD')

    def test_expired_grant_refused_before_probe(self):
        self.refuse(29194, until=int(time.time())-60)


if __name__ == '__main__':
    unittest.main()
