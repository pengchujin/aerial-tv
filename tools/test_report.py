"""Exercise report.sh's complete masking function with synthetic, offline text only."""
import os
from pathlib import Path
import re
import subprocess
import unittest

SCRIPT = Path(os.environ.get('REPORT_SCRIPT', Path(__file__).with_name('report.sh'))).resolve()


class ReportMaskTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Do not source the script: its top-level code would connect to a TV.
        match = re.search(r'^mask\(\) \{\n.*?^\}', SCRIPT.read_text(), re.M | re.S)
        if match is None:
            raise AssertionError('report.sh mask() function was not found')
        cls.function = match.group()

    def mask(self, text):
        env = dict(os.environ, LC_ALL='C')
        for name in ('SHELLOPTS', 'BASHOPTS', 'BASH_ENV'):
            env.pop(name, None)
        result = subprocess.run(['bash', '-c', 'set -euo pipefail\n' + self.function + '\nmask'],
                                input=text, capture_output=True, text=True, env=env, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, '')
        return result.stdout

    def test_adjacent_addresses_share_one_separator(self):
        for separator in (' ', ',', ':', '\t', ';'):
            with self.subTest(separator=separator):
                source = separator.join(('192.0.2.1', '198.51.100.2', '203.0.113.3')) + '\n'
                expected = separator.join(('<ip>', '<ip>', '<ip>')) + '\n'
                self.assertEqual(self.mask(source), expected)

    def test_addresses_on_separate_lines(self):
        self.assertEqual(self.mask('192.0.2.1\n198.51.100.2\n'), '<ip>\n<ip>\n')

    def test_long_adjacent_list_terminates_and_masks_every_address(self):
        source = ','.join('192.0.2.' + str(n) for n in range(1, 101)) + '\n'
        self.assertEqual(self.mask(source), ','.join(['<ip>'] * 100) + '\n')

    def test_address_boundaries_and_surrounding_text_are_kept(self):
        for source, expected in (
                ('192.0.2.1\n', '<ip>\n'),
                ('[192.0.2.1]\n', '[<ip>]\n'),
                ('(192.0.2.1),198.51.100.2\n', '(<ip>),<ip>\n'),
                ('from=192.0.2.1 to=198.51.100.2:5555\n', 'from=<ip> to=<ip>:5555\n'),
                ('version v1.2.3.4 peer 192.0.2.1\n', 'version v1.2.3.4 peer <ip>\n')):
            with self.subTest(source=source):
                self.assertEqual(self.mask(source), expected)

    def test_dotted_versions_identifiers_and_longer_numbers_are_unchanged(self):
        source = ('v1.2.3.4 1.2.3.4beta prefix192.0.2.1 192.0.2.1suffix '
                  '192.0.2.1.5 .192.0.2.1 192.0.2.1. 1192.0.2.1 192.0.2.1111 1.80\n')
        self.assertEqual(self.mask(source), source)

    def test_phone_tokens_and_mac_addresses_are_still_masked(self):
        source = ('http://192.0.2.1:8790/AbC123/api '
                  'aa:bb:cc:dd:ee:ff AA:BB:CC:DD:EE:FF :8790/Z9\n')
        self.assertEqual(self.mask(source), 'http://<ip>:8790/<token>/api <mac> <mac> :8790/<token>\n')

    def test_mixed_masks_do_not_leave_adjacent_addresses(self):
        source = ('192.0.2.1,198.51.100.2 http://203.0.113.3:8790/AbC123 '
                  '00:11:22:33:44:55 192.0.2.4:198.51.100.5\n')
        expected = '<ip>,<ip> http://<ip>:8790/<token> <mac> <ip>:<ip>\n'
        self.assertEqual(self.mask(source), expected)

    def test_masking_is_idempotent(self):
        source = '192.0.2.1 198.51.100.2 :8790/AbC123 aa:bb:cc:dd:ee:ff\n'
        masked = self.mask(source)
        self.assertEqual(self.mask(masked), masked)

    def test_empty_input_and_existing_markers(self):
        self.assertEqual(self.mask(''), '')
        self.assertEqual(self.mask('<ip> <token> <mac>\n'), '<ip> <token> <mac>\n')


if __name__ == '__main__':
    unittest.main()
