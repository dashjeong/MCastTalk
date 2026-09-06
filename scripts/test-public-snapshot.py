"""Synthetic regression fixtures; no real credentials or production data."""
import gzip
import importlib.util
import io
import pathlib
import tarfile
import unittest
import zipfile
import sys

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('gate', pathlib.Path(__file__).with_name('verify-public-snapshot.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class GateTest(unittest.TestCase):
    def test_safe_source(self):
        gate.inspect('src/example.kt', b'val token = createRandomToken()')

    def test_secret_patterns(self):
        for secret in [b'gh' + b'p_' + b'A' * 36,
                       b'eyJ' + b'A' * 12 + b'.' + b'B' * 20 + b'.' + b'C' * 24,
                       b'-----BEGIN ' + b'PRIVATE KEY-----']:
            with self.subTest(), self.assertRaises(ValueError):
                gate.inspect('src/example.kt', secret)

    def test_artifacts_and_paths(self):
        for name in ['release.apk', 'nested/device.log', 'device.logcat', '.env', '../secret', '/root/secret', 'docs/evidence/notes.txt']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                gate.inspect(name, b'plain')

    def test_compressed_secret(self):
        with self.assertRaises(ValueError):
            gate.inspect('data.gz', gzip.compress(b'gh' + b'p_' + b'X' * 36))

    def test_unreviewed_database(self):
        with self.assertRaises(ValueError):
            gate.inspect('app/src/main/assets/conversations.db', b'private data')

    def test_glossary_excluded_even_without_secrets(self):
        with self.assertRaises(ValueError):
            gate.inspect('app/src/main/assets/glossary/public-20260905.db.gz', gzip.compress(b'public terms'))

    def test_source_spreadsheets_excluded(self):
        for name in ['public-terms-en.xls', 'terms.xlsx', 'terms.xls.gz']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                gate.inspect(name, gzip.compress(b'public terms') if name.endswith('.gz') else b'public terms')

    def test_zip_hidden_log(self):
        b = io.BytesIO()
        with zipfile.ZipFile(b, 'w') as z:
            z.writestr('diagnostics/session.log', 'private content')
        with self.assertRaises(ValueError):
            gate.inspect('data.zip', b.getvalue())

    def test_tar_symlink(self):
        b = io.BytesIO()
        with tarfile.open(fileobj=b, mode='w') as t:
            item = tarfile.TarInfo('escape'); item.type = tarfile.SYMTYPE; item.linkname = '/tmp/outside'
            t.addfile(item)
        with self.assertRaises(ValueError):
            gate.inspect('data.tar', b.getvalue())

if __name__ == '__main__':
    unittest.main()
