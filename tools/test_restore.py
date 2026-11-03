"""Offline restore regression tests; every adb call is a stub, with no TV or network."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(os.environ.get('RESTORE_SCRIPT', Path(__file__).with_name('restore.sh'))).resolve()
LISTENER = 'app.orchard.tv/app.orchard.tv.guard.RevivalListener'
OTHER = 'org.example.first/org.example.first.Listener'
ANOTHER = 'org.example.second/org.example.second.Listener'


class RestoreListenerTest(unittest.TestCase):
    def run_restore(self, listeners, cmd_status=127):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / 'calls'
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$CALL_LOG"
case "$*" in
  connect*) echo connected ;;
  'shell cmd notification disallow_listener '*) exit "$CMD_STATUS" ;;
  'shell settings get secure enabled_notification_listeners') printf '%s\\n' "$LISTENERS" ;;
  'shell settings get secure screensaver_components') echo org.example/.Screensaver ;;
  'shell settings get secure enabled_accessibility_services') echo app.orchard.tv/app.orchard.tv.guard.HomeGuardService ;;
  'shell settings get global aerial_vt_home_pkg') echo null ;;
  'shell settings get global aerial_boot_app_componentname') echo null ;;
  'shell settings get system boot_app_componentname') echo null ;;
  'shell getprop persist.vt.use_home_pkg') ;;
  'shell pm path com.mitv.tvhome'|'shell pm path app.orchard.tv.lite') exit 1 ;;
  'shell cmd role remove-role-holder '*) exit "$CMD_STATUS" ;;
  'shell cmd package resolve-activity '*) echo org.example/.Home ;;
  'shell am broadcast '*|'shell pm revoke '*|'shell appops set '*|'shell am force-stop '*|\
  'shell settings delete '*|'shell settings put '*|'shell input keyevent KEYCODE_HOME') ;;
  *) echo "unexpected adb: $*" >&2; exit 99 ;;
esac
''')
            adb.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       TV='example.invalid', CALL_LOG=str(log),
                       LISTENERS=listeners, CMD_STATUS=str(cmd_status))
            for name in ('SHELLOPTS', 'BASHOPTS', 'BASH_ENV', 'POSIXLY_CORRECT'):
                env.pop(name, None)
            result = subprocess.run(['bash', str(SCRIPT)], env=env,
                                    capture_output=True, text=True, timeout=5)
            return result, log.read_text().splitlines()

    def assert_restored(self, result, calls):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('shell pm revoke app.orchard.tv android.permission.WRITE_SECURE_SETTINGS', calls)
        self.assertIn('shell am force-stop app.orchard.tv', calls)
        self.assertIn('shell input keyevent KEYCODE_HOME', calls)
        self.assertIn('Home now: org.example/.Home', result.stdout)
        self.assertNotIn('unexpected adb:', result.stderr)
        self.assertFalse(any(call.startswith('uninstall ') for call in calls))

    def test_sole_listener_is_deleted_when_cmd_is_unavailable(self):
        result, calls = self.run_restore(LISTENER)
        self.assert_restored(result, calls)
        self.assertIn('shell settings delete secure enabled_notification_listeners', calls)
        self.assertFalse(any(call.startswith('shell settings put secure enabled_notification_listeners ') for call in calls))

    def test_other_listeners_keep_their_order(self):
        for listeners in (':'.join((LISTENER, OTHER, ANOTHER)),
                          ':'.join((OTHER, LISTENER, ANOTHER)),
                          ':'.join((OTHER, ANOTHER, LISTENER))):
            with self.subTest(listeners=listeners):
                result, calls = self.run_restore(listeners)
                self.assert_restored(result, calls)
                self.assertIn('shell settings put secure enabled_notification_listeners ' + OTHER + ':' + ANOTHER, calls)
                self.assertNotIn('shell settings delete secure enabled_notification_listeners', calls)

    def test_absent_listener_does_not_change_the_setting(self):
        for listeners in (OTHER, 'null', ''):
            with self.subTest(listeners=listeners):
                result, calls = self.run_restore(listeners)
                self.assert_restored(result, calls)
                self.assertFalse(any(call.startswith('shell settings put secure enabled_notification_listeners ') for call in calls))
                self.assertNotIn('shell settings delete secure enabled_notification_listeners', calls)

    def test_successful_disallow_with_empty_setting_still_restores(self):
        result, calls = self.run_restore('null', cmd_status=0)
        self.assert_restored(result, calls)
        self.assertNotIn('shell settings delete secure enabled_notification_listeners', calls)


if __name__ == '__main__':
    unittest.main()
