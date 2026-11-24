# Review optional packages over ADB

Use Python 3.9+ and a connected, authorized TV. Every command takes the explicit ADB serial shown by `adb devices`. Inventory stays on your computer.

    python3 tools/packages.py SERIAL scan
    python3 tools/packages.py SERIAL preview
    python3 tools/packages.py SERIAL apply PACKAGE
    python3 tools/packages.py SERIAL check
    python3 tools/packages.py SERIAL restore PACKAGE

Scan inventories installed packages. Preview gives the effect of each candidate for the matched product. Apply accepts a single named candidate. The Z7X bug reporter may be needed for manufacturer support. The TCL boot-ad service may affect startup. Both entries were seen on hardware; their removal from normal use has not been tested. Confirm the consequences on your firmware before applying. The test fixture is a disposable app. Package state is recorded locally under `~/.aerial/package-state`, including the original firmware fingerprint. Preserve this directory to reverse your changes. Check shows whether a previously managed package changed or disappeared after an update. It does not reapply anything.
