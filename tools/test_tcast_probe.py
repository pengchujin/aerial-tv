"""Offline tests of the production T-Cast helper; all networking commands are stubs."""
import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('install.sh').resolve()


class TcastProbeTest(unittest.TestCase):
    def run_probe(self, platform, listening=True, push_status=0, missing_nc=False):
        # Source only the helper to avoid any real installer/TV actions.
        source = SCRIPT.read_text()
        helper = source[source.index('tcast_install() {'):source.index('\nBEFORE=$(installed_code)')]
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            apk = root / 'fixture.apk'
            apk.write_bytes(b'fixture')
            scripts = {
                'uname': '#!/usr/bin/env bash\necho "$PLATFORM"\n',
                'nc': '''#!/usr/bin/env bash
printf 'nc %s\\n' "$*" >> "$LOG"
[ "$MISSING_NC" != 1 ] || exit 127
expected=-w
[ "$PLATFORM" != Darwin ] || expected=-G
# Model the documented native nc options; a Linux -G must be rejected.
[ "$*" = "-z $expected 3 example.invalid 6553" ] || exit 64
[ "$LISTENING" = 1 ]
''',
                'adb': '''#!/usr/bin/env bash
printf 'adb %s\\n' "$*" >> "$LOG"
''',
                'python3': '''#!/usr/bin/env bash
printf 'python3 %s\\n' "$*" >> "$LOG"
case "$*" in
  '-m http.server '*) exit 0 ;;
  'tools/tcast-push.py '*) exit "$PUSH_STATUS" ;;
  *) exit 99 ;;
esac
''',
            }
            for name, body in scripts.items():
                path = root / name
                path.write_text(body)
                path.chmod(0o755)
            harness = root / 'harness.sh'
            harness.write_text('set -euo pipefail\n' + helper + '''
SERIAL=example.invalid:5555
PKG=app.orchard.tv
if tcast_install "$APK" 123; then
  echo TCAST_OK
else
  rc=$?
  echo "FALLBACK:$rc"
  exit "$rc"
fi
''')
            env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ['PATH'],
                       PLATFORM=platform, LISTENING='1' if listening else '0',
                       PUSH_STATUS=str(push_status), MISSING_NC='1' if missing_nc else '0',
                       LOG=str(root/'calls'), APK=str(apk), TMPDIR=str(root))
            result = subprocess.run(['bash', str(harness)], env=env,
                                    capture_output=True, text=True, timeout=5)
            return result, (root/'calls').read_text(), list(root.glob('tmp.*'))

    def test_linux_probe_reaches_tcast(self):
        result, calls, leftovers = self.run_probe('Linux')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('nc -z -w 3 example.invalid 6553', calls)
        self.assertIn('python3 tools/tcast-push.py', calls)
        self.assertIn('adb reverse --remove tcp:8765', calls)
        self.assertEqual(leftovers, [])

    def test_darwin_keeps_connection_timeout(self):
        result, calls, leftovers = self.run_probe('Darwin')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('nc -z -G 3 example.invalid 6553', calls)
        self.assertIn('python3 tools/tcast-push.py', calls)
        self.assertEqual(leftovers, [])

    def test_closed_port_still_falls_back_without_starting_server(self):
        for platform in ('Linux', 'Darwin'):
            with self.subTest(platform=platform):
                result, calls, leftovers = self.run_probe(platform, listening=False)
                self.assertEqual(result.returncode, 1)
                self.assertNotIn('python3', calls)
                self.assertNotIn('adb', calls)
                self.assertEqual(leftovers, [])

    def test_missing_nc_still_falls_back(self):
        result, calls, leftovers = self.run_probe('Linux', missing_nc=True)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn('python3', calls)
        self.assertNotIn('adb', calls)
        self.assertEqual(leftovers, [])

    def test_push_failure_preserves_return_code_and_cleanup(self):
        for platform in ('Linux', 'Darwin'):
            with self.subTest(platform=platform):
                result, calls, leftovers = self.run_probe(platform, push_status=42)
                self.assertEqual(result.returncode, 42)
                self.assertIn('adb reverse --remove tcp:8765', calls)
                self.assertEqual(leftovers, [])


if __name__ == '__main__':
    unittest.main()

