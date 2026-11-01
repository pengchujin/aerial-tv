#!/usr/bin/env python3
"""Install an APK on a TCL TV through TCL's own 多屏互动 (com.tcl.MultiScreenInteraction_TV).

TCL's firmware refuses every ADB install, but 多屏互动 runs as the system user and installs apps that
TCL's phone app pushes to it. This speaks that phone protocol: it asks the TV to download the APK
from `url` and install it. The TV shows one prompt (同意 / 拒绝); after 同意 it installs silently.

    tools/tcast-push.py <tv-ip> <url> <package> <label> <versionCode> [timeout-seconds]

The protocol, read from 多屏互动 5.30 on TCL MT5879 (see docs/devices/tcl-mt5879-cn.md):
- TCP port 6553; every message is a 4-byte big-endian length followed by UTF-8 text, fields split by ">>".
- First message "159>>1>>0" opens the session without encryption.
- "150>>" is the heartbeat; the TV answers "150>>YES" and drops sessions that go quiet.
- "223>>INSTALL>>package>>label>>url>>2>>versionCode>>sizeMB>>appMd5>>signatureMd5>>icon>>apkPathMd5":
  source 2 with an apkPathMd5 marks a phone-local APK, which skips the store lookup and asks the viewer.
  The TV answers "223>>INSTALL>>package>>label>>confirm_pending|denied|installing|installed>>apkPathMd5",
  then, once the viewer agrees, "223>>PROGRESS>>package>>label>>stage>>percent>>apkPathMd5":
  stage 1 downloading, 3 installing, 5 installed, 2 failed.

Exits 0 once the TV reports the app installed, 1 if the viewer refused, the install failed, or
nothing happened in time.
"""
import hashlib
import socket
import struct
import sys
import time

PORT = 6553


def send(sock, text):
    data = text.encode()
    sock.sendall(struct.pack(">i", len(data)) + data)


def messages(buf):
    """Yields complete messages already in buf, and returns what is left over."""
    out = []
    while len(buf) >= 4:
        n = struct.unpack(">i", buf[:4])[0]
        if n < 0:
            raise ValueError("negative T-Cast message length: {}".format(n))
        if len(buf) < 4 + n:
            break
        out.append(buf[4:4 + n].decode("utf-8", "replace"))
        buf = buf[4 + n:]
    return out, buf


def main():
    if len(sys.argv) < 6:
        print(__doc__, file=sys.stderr)
        return 2
    tv, url, package, label, version = sys.argv[1:6]
    timeout = float(sys.argv[6]) if len(sys.argv) > 6 else 180

    sock = socket.create_connection((tv, PORT), timeout=5)
    sock.settimeout(1)
    send(sock, "159>>1>>0")
    path_md5 = hashlib.md5(url.encode()).hexdigest()
    send(sock, ">>".join(["223", "INSTALL", package, label, url, "2", version, "20", "", "", "", path_md5]))

    buf = b""
    last_beat = time.time()
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        except socket.timeout:
            pass
        try:
            found, buf = messages(buf)
        except ValueError as exc:
            sock.close()
            print("Invalid response from the TV: {}.".format(exc), file=sys.stderr)
            return 1
        for msg in found:
            parts = msg.split(">>")
            if len(parts) < 5 or parts[0] != "223" or parts[2] != package:
                continue
            if parts[1] == "INSTALL":
                if parts[4] == "confirm_pending":
                    print("The TV is asking: choose 同意 (Agree) on the TV.", flush=True)
                elif parts[4] == "denied":
                    print("Refused on the TV.", file=sys.stderr)
                    return 1
            elif parts[1] == "PROGRESS":
                stage = parts[4]
                if stage == "5":
                    print("Installed.", flush=True)
                    return 0
                if stage == "2":
                    print("The TV could not download or install the APK.", file=sys.stderr)
                    return 1
        # The TV drops a quiet session, and with it the pending install.
        if time.time() - last_beat > 5:
            send(sock, "150>>")
            last_beat = time.time()
    sock.close()
    print("Timed out waiting for the TV.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
