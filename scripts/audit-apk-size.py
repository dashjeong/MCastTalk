"""Record comparable APK components and fail on accidental dependency bloat."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def inspect(path):
    groups = {key: 0 for key in ('dex', 'native', 'assets', 'resources', 'other')}
    with zipfile.ZipFile(path) as archive:
        rows = archive.infolist()
        for row in rows:
            name = row.filename
            kind = ('dex' if name.endswith('.dex') else 'native' if name.startswith('lib/') else
                    'assets' if name.startswith('assets/') else 'resources' if name.startswith('res/') or name == 'resources.arsc' else 'other')
            groups[kind] += row.compress_size
        top = [{'path': row.filename, 'packed_bytes': row.compress_size, 'unpacked_bytes': row.file_size}
               for row in sorted(rows, key=lambda row: row.compress_size, reverse=True)[:20]]
    with path.open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        sha = digest.hexdigest()
    return {'bytes': path.stat().st_size, 'sha256': sha,
            'unpacked_dex_bytes': sum(row.file_size for row in rows if row.filename.endswith('.dex')),
            'packed_components': groups, 'largest_files': top}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = inspect(args.apk)
    if args.baseline:
        baseline = inspect(args.baseline)
        result['baseline'] = {'bytes': baseline['bytes'], 'sha256': baseline['sha256']}
        result['delta_bytes'] = result['bytes'] - baseline['bytes']
    result['budgets'] = {'apk_bytes': 110_000_000, 'dex_bytes': 18_000_000}
    result['within_budget'] = result['bytes'] <= 110_000_000 and result['unpacked_dex_bytes'] <= 18_000_000
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if key != 'largest_files'}))
    raise SystemExit(0 if result['within_budget'] else 'APK size budget exceeded; inspect the component report')
