import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import packages


class ReviewTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.state = 0
        self.serial = "device-one"
        self.present = True
        self.fail = False
        self.package = "org.example.fixture"
        patcher = patch.dict(packages.CATALOG, {("dev", "product"): {self.package: "fixture"}})
        patcher.start()
        self.addCleanup(patcher.stop)

    def shell(self, *args):
        if args[0] == "getprop":
            return {"ro.serialno": self.serial, "ro.product.device": "dev",
                    "ro.product.name": "product", "ro.build.fingerprint": "build"}[args[1]]
        if args[0] == "am":
            return "0"
        if args[:2] == ("pm", "list"):
            return "package:" + self.package if self.present else ""
        if args[0] == "dumpsys":
            return f"  User 0: installed=true enabled={self.state}" if self.present else ""
        if self.fail:
            raise TimeoutError()
        self.state = next(k for k, v in packages.MODES.items() if v == args[1])
        return ""

    def perform(self, action):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            packages.run(self.shell, self.root, action, self.package)
        return output.getvalue()

    def test_retry_and_restore(self):
        self.state = 1
        self.fail = True
        with self.assertRaises(TimeoutError):
            self.perform("apply")
        self.fail = False
        self.perform("apply")
        self.perform("apply")
        self.assertEqual(3, self.state)
        self.perform("restore")
        self.assertEqual(1, self.state)

    def test_other_device_and_missing_package(self):
        self.perform("apply")
        self.serial = "other"
        with self.assertRaises(ValueError):
            self.perform("restore")
        self.serial = "device-one"
        self.present = False
        self.assertIn('"current": null', self.perform("check"))

    def test_previously_disabled(self):
        self.state = 3
        self.perform("apply")
        self.assertFalse(list(self.root.glob("*.json")))
