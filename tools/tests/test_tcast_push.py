"""Offline protocol regression tests; no TV or network connection is used."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import struct
import subprocess
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

SCRIPT = Path(os.environ.get('TCAST_SCRIPT', Path(__file__).resolve().parents[1] / 'tcast-push.py'))
spec = importlib.util.spec_from_file_location('tcast_push', SCRIPT)
tcast = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tcast)

def frame(text):
    payload = text.encode('utf-8')
    return struct.pack('>i', len(payload)) + payload

class MessagesTest(unittest.TestCase):
    def test_empty_buffer(self):
        self.assertEqual(tcast.messages(b''), ([], b''))

    def test_empty_message(self):
        self.assertEqual(tcast.messages(frame('')), ([''], b''))

    def test_single_utf8_message(self):
        self.assertEqual(tcast.messages(frame('223>>安装')), (['223>>安装'], b''))

    def test_coalesced_messages(self):
        self.assertEqual(tcast.messages(frame('150>>YES') + frame('223>>PROGRESS')), (['150>>YES', '223>>PROGRESS'], b''))

    def test_fragmented_header(self):
        data = frame('150>>YES')
        for split in (1, 2, 3):
            with self.subTest(split=split):
                found, left = tcast.messages(data[:split])
                self.assertEqual((found, left), ([], data[:split]))
                self.assertEqual(tcast.messages(left + data[split:]), (['150>>YES'], b''))

    def test_fragmented_body(self):
        data = frame('150>>YES')
        found, left = tcast.messages(data[:-1])
        self.assertEqual((found, left), ([], data[:-1]))
        self.assertEqual(tcast.messages(left + data[-1:]), (['150>>YES'], b''))

    def test_complete_then_incomplete(self):
        trailing = frame('223>>PROGRESS')[:-2]
        self.assertEqual(tcast.messages(frame('150>>YES') + trailing), (['150>>YES'], trailing))

    def test_negative_lengths_terminate(self):
        # A child timeout also makes this safe to run against the original bug.
        code = '''import importlib.util, struct, sys
spec = importlib.util.spec_from_file_location('tcast_push', sys.argv[1])
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
try:
    m.messages(struct.pack('>i', int(sys.argv[2])))
except ValueError:
    sys.exit(0)
sys.exit(1)
'''
        for size in (-1, -4, -(2**31)):
            with self.subTest(size=size):
                result = subprocess.run([sys.executable, '-c', code, str(SCRIPT), str(size)], capture_output=True, timeout=1)
                self.assertEqual(result.returncode, 0, result.stderr.decode())

class MainTest(unittest.TestCase):
    def run_main(self, response):
        sock = Mock()
        sock.recv.side_effect = [response, b'']
        err, out = io.StringIO(), io.StringIO()
        args = ['tcast-push.py', 'unused.invalid', 'https://example.invalid/a.apk', 'app.orchard.tv', 'Aerial', '97']
        with patch.object(tcast.socket, 'create_connection', return_value=sock), patch.object(sys, 'argv', args), contextlib.redirect_stderr(err), contextlib.redirect_stdout(out):
            result = tcast.main()
        return result, sock, out.getvalue(), err.getvalue()

    def test_invalid_response_exits_cleanly(self):
        # -1 returns immediately in the old parser; unlike -4 it cannot hang main.
        result, sock, out, err = self.run_main(struct.pack('>i', -1))
        self.assertEqual(result, 1)
        sock.close.assert_called_once()
        self.assertIn('Invalid response from the TV', err)
        self.assertIn('-1', err)
        self.assertNotIn('Traceback', err)
        self.assertNotIn('Installed.', out)

    def test_success_still_succeeds(self):
        result, _, out, err = self.run_main(frame('223>>PROGRESS>>app.orchard.tv>>Aerial>>5>>100'))
        self.assertEqual(result, 0)
        self.assertIn('Installed.', out)
        self.assertEqual(err, '')

    def test_denied_still_fails(self):
        result, _, out, err = self.run_main(frame('223>>INSTALL>>app.orchard.tv>>Aerial>>denied'))
        self.assertEqual(result, 1)
        self.assertIn('Refused on the TV.', err)

    def test_failed_install_still_fails(self):
        result, _, out, err = self.run_main(frame('223>>PROGRESS>>app.orchard.tv>>Aerial>>2>>0'))
        self.assertEqual(result, 1)
        self.assertIn('could not download or install', err)

    def test_refused_install_fails(self):
        # TCL MT5879 after 同意 on aerial-standard.apk: download to 100, then stage 4 (issue #1163).
        result, _, out, err = self.run_main(frame('223>>PROGRESS>>app.orchard.tv>>Aerial>>3>>0') + frame('223>>PROGRESS>>app.orchard.tv>>Aerial>>4>>2'))
        self.assertEqual(result, 1)
        self.assertIn('refused to install', err)
        self.assertNotIn('Timed out', err)


class ClockTest(unittest.TestCase):
    def run_wait(self, wall_jump, timeout):
        elapsed = 0
        sent = []
        sock = Mock()

        def receive(_size):
            nonlocal elapsed
            elapsed += 1
            # A deterministic bound keeps regression tests safe on the old wall-clock loop.
            if elapsed > timeout + 1:
                self.fail('wait exceeded the timeout after a wall-clock adjustment')
            raise tcast.socket.timeout()

        sock.recv.side_effect = receive
        sock.sendall.side_effect = lambda packet: sent.append((elapsed, packet))
        clock = SimpleNamespace(
            monotonic=lambda: 42 + elapsed,
            time=lambda: 1000 + elapsed + (wall_jump if elapsed >= 2 else 0),
        )
        args = ['tcast-push.py', 'unused.invalid', 'https://example.invalid/a.apk',
                'app.orchard.tv', 'Aerial', '97', str(timeout)]
        err = io.StringIO()
        with patch.object(tcast.socket, 'create_connection', return_value=sock), \
                patch.object(tcast, 'time', clock), patch.object(sys, 'argv', args), \
                contextlib.redirect_stderr(err):
            result = tcast.main()
        self.assertEqual(result, 1)
        self.assertIn('Timed out waiting for the TV.', err.getvalue())
        sock.close.assert_called_once()
        return elapsed, [at for at, packet in sent if packet == frame('150>>')]

    def test_normal_timeout(self):
        elapsed, _ = self.run_wait(0, 3)
        self.assertEqual(elapsed, 3)

    def test_backward_clock_does_not_extend_timeout(self):
        elapsed, _ = self.run_wait(-100, 3)
        self.assertEqual(elapsed, 3)

    def test_forward_clock_does_not_shorten_timeout(self):
        elapsed, _ = self.run_wait(100, 3)
        self.assertEqual(elapsed, 3)

    def test_normal_heartbeat(self):
        elapsed, beats = self.run_wait(0, 13)
        self.assertEqual(elapsed, 13)
        self.assertEqual(beats, [6, 12])

    def test_backward_clock_does_not_pause_heartbeat(self):
        elapsed, beats = self.run_wait(-100, 13)
        self.assertEqual(elapsed, 13)
        self.assertEqual(beats, [6, 12])

    def test_forward_clock_does_not_trigger_early_heartbeat(self):
        elapsed, beats = self.run_wait(100, 13)
        self.assertEqual(elapsed, 13)
        self.assertEqual(beats, [6, 12])

if __name__ == '__main__':
    unittest.main()
