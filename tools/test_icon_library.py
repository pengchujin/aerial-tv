"""tools/icon-library.py: the site's icon index v2 (#706), several credited pictures an app."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('icon-library.py').resolve()


class IconLibraryTest(unittest.TestCase):
    def build(self, files, sources, defaults=None, names=None):
        """Runs the script on [files] (path → bytes) under a fake repo; (exit code, index, out dir)."""
        tmp = Path(tempfile.mkdtemp())
        (tmp/'tools').mkdir()
        (tmp/'tools/icon-library.py').write_bytes(SCRIPT.read_bytes())
        for path, data in files.items():
            (tmp/path).parent.mkdir(parents=True, exist_ok=True)
            (tmp/path).write_bytes(data)
        manifest = tmp/'tools/icon-sources.json'
        spec = {'sources': sources, 'defaults': defaults or {}}
        if names is not None:
            (tmp/'tools/names.tsv').write_text(names, encoding='utf-8')
            spec['names'] = 'tools/names.tsv'
        manifest.write_text(json.dumps(spec))
        out = tmp/'site/icons'
        run = subprocess.run([sys.executable, '-I', str(tmp/'tools/icon-library.py'), str(manifest), str(out)],
                             capture_output=True, text=True)
        index = json.loads((out/'index.json').read_text()) if run.returncode == 0 else None
        return run, index, out

    SOURCES = [
        {'key': 'le5inioo', 'dir': 'local/icons/le5inioo', 'name': 'le5inioo', 'url': 'https://github.com/le5inioo'},
        {'key': 'aerial', 'dir': 'local/icons/aerial', 'name': 'Aerial', 'names': {'zh': 'Aerial 自制'}},
    ]

    def test_choices_in_source_order_with_credits(self):
        run, index, out = self.build({
            'local/icons/le5inioo/com.a.b.png': b'one',
            'local/icons/le5inioo/com.a.b@2.png': b'two',
            'local/icons/le5inioo/notapackage.png': b'skip',
            'local/icons/le5inioo/README.md': b'skip',
            'local/icons/aerial/com.a.b.jpg': b'three',
            'local/icons/aerial/com.c.d.jpg': b'four',
        }, self.SOURCES)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(index['v'], 2)
        self.assertEqual([c['id'] for c in index['choices']['com.a.b']], ['le5inioo', 'le5inioo-2', 'aerial'])
        self.assertEqual([c['by'] for c in index['choices']['com.a.b']], ['le5inioo', 'le5inioo', 'aerial'])
        # What Aerial 1.90 and older read: the default only.
        self.assertEqual(index['icons']['com.a.b'], index['choices']['com.a.b'][0]['p'])
        self.assertEqual(set(index['icons']), {'com.a.b', 'com.c.d'})
        self.assertEqual(index['credits']['le5inioo'], {'name': 'le5inioo', 'url': 'https://github.com/le5inioo'})
        self.assertEqual(index['credits']['aerial'], {'name': 'Aerial', 'zh': 'Aerial 自制'})
        for listed in index['choices'].values():
            for c in listed:
                self.assertRegex(c['p'], r'^p/[A-Za-z0-9_.]+-[0-9a-f]{8}\.(png|jpg)$')
                self.assertTrue((out/c['p']).exists())

    def test_defaults_reorder(self):
        run, index, _ = self.build({
            'local/icons/le5inioo/com.a.b.png': b'one',
            'local/icons/aerial/com.a.b.jpg': b'three',
        }, self.SOURCES, {'com.a.b': 'aerial'})
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual([c['id'] for c in index['choices']['com.a.b']], ['aerial', 'le5inioo'])
        self.assertTrue(index['icons']['com.a.b'].endswith('.jpg'))

    def test_unknown_default_stops(self):
        run, _, _ = self.build({'local/icons/aerial/com.a.b.jpg': b'x', 'local/icons/le5inioo/x.y.png': b'y'},
                               self.SOURCES, {'com.a.b': 'le5inioo'})
        self.assertNotEqual(run.returncode, 0)

    def test_missing_source_stops(self):
        # Deploying without a source would take its pictures off the site.
        run, _, _ = self.build({'local/icons/aerial/com.a.b.jpg': b'x'}, self.SOURCES)
        self.assertNotEqual(run.returncode, 0)
        self.assertIn('local/icons/le5inioo is missing', run.stderr)

    def test_names_of_apps_with_pictures(self):
        files = {'local/icons/le5inioo/com.a.b.png': b'one', 'local/icons/aerial/com.c.d.jpg': b'two'}
        run, index, _ = self.build(files, self.SOURCES, names=(
            '# a comment\n'
            'com.a.b\t云视听小电视\tBilibili\t哔哩哔哩 B站\n'
            'com.c.d\t天气\t-\n'
            'com.not.here\t别的\tOther\n'))
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(index['names'], {
            'com.a.b': {'zh': '云视听小电视', 'en': 'Bilibili', 'also': '哔哩哔哩 B站'},
            'com.c.d': {'zh': '天气'},
        })
        # No names file: an empty table, as before.
        run, index, _ = self.build(files, self.SOURCES)
        self.assertEqual(index['names'], {})

    def test_bad_names_line_stops(self):
        files = {'local/icons/le5inioo/com.a.b.png': b'one', 'local/icons/aerial/com.c.d.jpg': b'two'}
        for bad in ('com.a.b only-two\n', 'not a package\tx\ty\n', 'com.a.b\ta\tb\ncom.a.b\tc\td\n'):
            run, _, _ = self.build(files, self.SOURCES, names=bad)
            self.assertNotEqual(run.returncode, 0, bad)


if __name__ == '__main__':
    unittest.main()
