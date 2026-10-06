"""Synchronize explicitly, or verify the committed mobile resource snapshot."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil

desktop = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--verify', action='store_true', help='Verify committed embeds without replacing them from another source snapshot')
args = parser.parse_args()
if args.verify:
    recorded = json.loads((desktop / 'mobile/manifest.json').read_text())
    actual = {}
    for group in ('listener', 'speaker'):
        for path in sorted((desktop / 'mobile' / group).glob('*')):
            if path.is_file() and path.suffix in ('.html', '.js', '.css'):
                actual[f'{group}/{path.name}'] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not actual or actual != recorded:
        raise SystemExit('Committed mobile resources differ from their verification manifest')
    print(f'Verified {len(actual)} committed mobile web resources; no files replaced')
    raise SystemExit(0)
source = desktop.parent / "core/server/src/main/assets"
if any(not (source / group).is_dir() for group in ('listener', 'speaker')):
    raise SystemExit('Mobile source directories missing; existing embeds preserved')
manifest = {}
for group in ("listener", "speaker"):
    target = desktop / "mobile" / group
    target.mkdir(parents=True, exist_ok=True)
    for path in sorted((source / group).glob("*")):
        if path.is_file() and path.suffix in (".html", ".js", ".css"):
            shutil.copyfile(path, target / path.name)
            manifest[f"{group}/{path.name}"] = hashlib.sha256(path.read_bytes()).hexdigest()
(desktop / "mobile/manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Synced {len(manifest)} unchanged mobile web resources")
