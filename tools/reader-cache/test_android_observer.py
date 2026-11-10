import json
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from android_observer import observe_android


class AndroidObserverTest(unittest.TestCase):
    def test_modes_execute_only_selected_operations_and_keep_host_boundaries(self):
        for mode,expected in (('both',['stage','meminfo']),('stage',['stage']),('meminfo',['meminfo'])):
            commands=[]
            def run(command,**_):
                commands.append(command)
                body=json.dumps(dict(runId='current',stage='unit-stage')) if 'run-as' in command else 'unit meminfo'
                return SimpleNamespace(returncode=0,stdout=body)
            with self.subTest(mode=mode):
                row=observe_android(['adb-unit','-s','emulator-unit'],'isolated-package','current',mode,run)
                self.assertEqual(expected,list(row['operations']))
                self.assertEqual(['stage' if 'run-as' in command else 'meminfo' for command in commands],expected)
                self.assertEqual(mode in ('both','meminfo'),'meminfo' in row)
                self.assertEqual('unit-stage' if mode in ('both','stage') else 'test_process_running',row['stage'])
                for span in row['operations'].values():
                    self.assertLessEqual(row['hostMonotonicNs'],span['invokedNs'])
                    self.assertLessEqual(span['invokedNs'],span['returnedNs'])
                    self.assertEqual(0,span['exit'])

    def test_missing_stale_or_malformed_stage_is_recorded_without_inventing_a_match(self):
        for status,body in ((1,''),(0,'invalid'),(0,'[]'),(0,'{"runId":"old","stage":"old-stage"}')):
            row=observe_android(['adb-unit'],'isolated-package','current','stage',
                lambda *_,**kwargs:SimpleNamespace(returncode=status,stdout=body))
            self.assertFalse(row['stageRunMatched'])
            self.assertEqual('test_process_running',row['stage'])
            self.assertEqual(status,row['operations']['stage']['exit'])
            self.assertNotIn('meminfo',row)

    def test_command_exception_is_retained_and_prevents_the_next_operation(self):
        run=Mock(side_effect=OSError('unit failure'))
        row=observe_android(['adb-unit'],'isolated-package','current','both',run)
        run.assert_called_once()
        self.assertEqual(['stage'],list(row['operations']))
        self.assertEqual('OSError',row['operations']['stage']['error'])
        self.assertLessEqual(row['operations']['stage']['invokedNs'],row['operations']['stage']['returnedNs'])

    def test_disabled_mode_never_executes_an_observation(self):
        run=Mock()
        with self.assertRaises(ValueError): observe_android(['adb-unit'],'isolated-package','current','none',run)
        run.assert_not_called()


if __name__=='__main__': unittest.main()
