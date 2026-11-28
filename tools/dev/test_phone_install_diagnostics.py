"""Installer failures must remain actionable without retaining private output."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'phone_install_runner', Path(__file__).with_name('check-phone-reconnect.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class InstallDiagnosticsTest(unittest.TestCase):
    def test_known_code_without_private_reason(self):
        result = runner.installation_diagnostic(
            'Failure [INSTALL_FAILED_USER_RESTRICTED: private learning content token=secret]')
        self.assertEqual(result, dict(packageManagerCodes=['INSTALL_FAILED_USER_RESTRICTED'],
                                     passwordPrompt=False, transportFailure=False))

    def test_unknown_code_and_arbitrary_output_stay_unknown(self):
        result = runner.installation_diagnostic('Failure [PERSONAL_SECRET]\nprivate text')
        self.assertEqual(result['packageManagerCodes'], [])
        self.assertNotIn('SECRET', str(result))
        self.assertNotIn('private', str(result))

    def test_password_and_transport_are_booleans_only(self):
        result = runner.installation_diagnostic(
            '手机要求输入账号密码，必须人来点\nerror: device private-address not found')
        self.assertTrue(result['passwordPrompt'])
        self.assertTrue(result['transportFailure'])
        self.assertNotIn('private-address', str(result))

    def test_package_query_handles_absent_and_prefix_match(self):
        package = runner.PACKAGE
        self.assertFalse(runner.package_present('', package+'.test'))
        both = f'package:{package}\npackage:{package}.test\n'
        self.assertTrue(runner.package_present(both, package))
        self.assertTrue(runner.package_present(both, package+'.test'))
        self.assertFalse(runner.package_present(f'package:{package}\n', package+'.test'))

    def test_package_query_errors_never_count_as_absence(self):
        for output in ('Error: permission denied', 'package:com.private.app'):
            with self.assertRaises(ValueError):
                runner.package_present(output, runner.PACKAGE+'.test')


if __name__ == '__main__':
    unittest.main()
