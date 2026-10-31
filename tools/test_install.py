"""Offline regression tests: every adb call is a stub; no TV or network is used."""
import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('install.sh').resolve()


class InstallFailureTest(unittest.TestCase):
    def run_install(self, failure, status, flavor='standard'):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            log = root / 'calls'
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$CALL_LOG"
case "$*" in
  devices) printf 'List of devices attached\\nexample.invalid:5555 device\\n' ;;
  connect*) echo connected ;;
  get-state) echo device ;;
  'shell getprop ro.build.version.sdk') echo 30 ;;
  'shell dumpsys package app.orchard.tv')
    if [ -f "$MOCK_STATE" ]; then echo versionCode=2; else echo versionCode=1; fi ;;
  install*) echo "$INSTALL_OUTPUT"; exit "$INSTALL_STATUS" ;;
  push*) touch "$MOCK_STATE" ;;
  'shell dumpsys notification') echo app.orchard.tv/app.orchard.tv.guard.RevivalListener ;;
  'shell dumpsys accessibility') echo label=Aerial ;;
  'shell settings get secure screensaver_enabled') echo 0 ;;
  'shell settings get secure enabled_accessibility_services') echo app.orchard.tv/app.orchard.tv.guard.HomeGuardService ;;
  'shell getprop persist.vt.use_home_pkg'|'shell pm path com.mitv.tvhome'|'shell pm path com.dangbeimarket') ;;
  'shell cmd package resolve-activity'*) echo app.orchard.tv/.MainActivity ;;
  'shell am start -W'*) echo 'Status: ok' ;;
  'shell pm grant '*|'shell appops set '*|'shell cmd notification '*|'shell cmd package compile '*|'shell cmd role '*|'shell settings put '*|'shell content call '*|'shell rm -f /sdcard/Download/Aerial.apk') ;;
  *) echo "unexpected adb: $*" >&2; exit 99 ;;
esac
''')
            adb.chmod(0o755)
            for name, command in [('sleep', 'exit 0'), ('nc', 'exit 1'),
                                  ('curl', 'echo "unexpected network" >&2; exit 99')]:
                stub = root / name
                stub.write_text('#!/usr/bin/env bash\n' + command + '\n')
                stub.chmod(0o755)
            env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ['PATH'],
                       TV='example.invalid', APK_FILE=str(root/'test.apk'),
                       CALL_LOG=str(log), INSTALL_OUTPUT=failure,
                       INSTALL_STATUS=str(status), STAY_CONNECTED='1',
                       MOCK_STATE=str(root/'pushed'), BUILD='0', KEEP_ADB='0')
            result = subprocess.run(['bash', str(SCRIPT), flavor], env=env,
                                    capture_output=True, text=True, timeout=5)
            return result, log.read_text()

    def test_nonzero_certificate_mismatch_reaches_explanation(self):
        result, calls = self.run_install('Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]', 1)
        self.assertEqual(result.returncode, 1)
        self.assertIn('signed with another key', result.stderr)
        self.assertNotIn('pm grant', calls)
        self.assertNotIn('uninstall', calls)

    def test_nonzero_inconsistent_certificate_reaches_explanation(self):
        result, _ = self.run_install('Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]', 1)
        self.assertIn('signed with another key', result.stderr)

    def test_zero_exit_failure_is_still_rejected(self):
        result, calls = self.run_install('Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]', 0)
        self.assertEqual(result.returncode, 1)
        self.assertIn('signed with another key', result.stderr)
        self.assertNotIn('pm grant', calls)

    def test_nonzero_generic_failure_prints_error_and_guard_hint(self):
        result, calls = self.run_install('Failure [INSTALL_FAILED_INVALID_APK]', 7)
        self.assertEqual(result.returncode, 1)
        self.assertIn('INSTALL_FAILED_INVALID_APK', result.stderr)
        self.assertIn('Try:', result.stderr)
        self.assertNotIn('pm grant', calls)

    def test_success_reaches_home_confirmation(self):
        result, calls = self.run_install('Success', 0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Aerial is the home screen', result.stdout)
        self.assertIn('shell pm grant', calls)

    def test_nonzero_verifier_failure_reaches_existing_tcl_fallback(self):
        result, calls = self.run_install('Failure [INSTALL_FAILED_VERIFICATION_FAILURE]', 1, 'guard')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('now in Download', result.stdout)
        self.assertIn('Home Guard is running', result.stdout)
        self.assertIn('push ', calls)


if __name__ == '__main__':
    unittest.main()
