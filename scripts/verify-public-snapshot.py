"""Fail closed on common release contamination; never print secret values.

This gate complements human privacy/license review and a full object-store scan.
It is not proof that all possible secrets or personal data have been identified.
"""
import gzip
import io
import pathlib
import re
import subprocess
import sys
import tarfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAX_MEMBER = 128 * 1024 * 1024
PATTERNS = [re.compile(p) for p in (
    rb'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}',
    rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})',
    rb'AIza[0-9A-Za-z0-9_-]{35}',
    rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----',
    rb'(?:AKIA|ASIA)[A-Z0-9]{16}',
    rb'xox[baprs]-[0-9A-Za-z-]{20,}',
    rb'sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{40,}',
)]
FORBIDDEN_PARTS = {'.git', '.codex', '.agents', '.signing', '.gradle', '.kotlin',
                   'build', 'evidence', 'diagnostics', 'node_modules', '__pycache__'}
FORBIDDEN_SUFFIXES = {'.apk', '.aab', '.log', '.logcat', '.jsonl', '.jks', '.keystore',
                      '.p12', '.pfx', '.hprof', '.jfr', '.dmp', '.sqlite', '.sqlite3',
                      '.db', '.xls', '.xlsx'}
FORBIDDEN_NAMES = {'local.properties', 'keystore.properties', 'google-services.json',
                   'secrets.properties', 'id_rsa', 'id_ed25519'}

def check_path(name):
    p = pathlib.PurePosixPath(name)
    if p.is_absolute() or '..' in p.parts or any(x in FORBIDDEN_PARTS for x in p.parts):
        raise ValueError('forbidden path')
    if p.suffix.lower() in FORBIDDEN_SUFFIXES or p.name in FORBIDDEN_NAMES:
        raise ValueError('forbidden artifact')
    if p.name == '.env' or (p.name.startswith('.env.') and p.name != '.env.example'):
        raise ValueError('environment file')

def inspect(name, data, depth=0):
    check_path(name)
    if any(p.search(data) for p in PATTERNS):
        raise ValueError('credential pattern')
    if depth > 5 or len(data) > MAX_MEMBER:
        raise ValueError('inspection limit')
    if data.startswith(b'PK\x03\x04'):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for member in z.infolist():
                if member.is_dir():
                    continue
                if member.file_size > MAX_MEMBER or (member.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError('archive size or symlink')
                inspect(member.filename, z.read(member), depth + 1)
    elif data.startswith(b'\x1f\x8b'):
        with gzip.GzipFile(fileobj=io.BytesIO(data)) as z:
            raw = z.read(MAX_MEMBER + 1)
        inspect(name.removesuffix('.gz'), raw, depth + 1)
    elif len(data) > 262 and data[257:262] == b'ustar':
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:') as t:
            for member in t:
                check_path(member.name)
                if member.isfile():
                    if member.size > MAX_MEMBER:
                        raise ValueError('archive size limit')
                    inspect(member.name, t.extractfile(member).read(), depth + 1)
                elif not member.isdir():
                    raise ValueError('archive link or special file')

def main():
    if (ROOT / '.git').exists():
        names = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
        names = [n for n in names if n]
        # A linked worktree or object alternates can silently reattach private history.
        if not (ROOT / '.git').is_dir() or (ROOT / '.git/objects/info/alternates').exists():
            raise ValueError('linked Git storage')
    else:
        names = [p.relative_to(ROOT).as_posix() for p in ROOT.rglob('*') if p.is_file() or p.is_symlink()]
    failed = 0
    for name in sorted(names):
        try:
            p = ROOT / name
            if p.is_symlink():
                raise ValueError('symlink')
            inspect(name, p.read_bytes())
        except Exception as error:
            # Never include exception strings from parsers: they can contain data.
            print('FAIL:', name, type(error).__name__)
            failed += 1
    if failed:
        raise SystemExit(1)
    print(f'PASS: {len(names)} source files; credential/artifact/archive checks')

if __name__ == '__main__':
    main()
