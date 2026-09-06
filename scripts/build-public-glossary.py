"""Read NIKL public-terminology XLS exports; build the offline SQLite data asset.

Official source & portal:
- 국립국어원 공공언어 통합 지원 시스템: https://publang.korean.go.kr
- 국립국어원 공식 누리집: https://www.korean.go.kr
- 공공데이터포털: https://www.data.go.kr

Analysis-only dependency: xlrd==2.0.2. No macros/formulas are executed.
Usage: python scripts/build-public-glossary.py INPUT_DIRECTORY
"""
import collections
import gzip
import hashlib
import json
import pathlib
import sqlite3
import sys
import tempfile
import unicodedata
import xlrd

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = {
    "en": "public-terms-en.xls",
    "zh": "public-terms-zh.xls",
    "ja": "public-terms-ja.xls",
}

def main():
    directory = pathlib.Path(sys.argv[1])
    report = {"source": "국립국어원 공공용어 번역 통합 데이터", "languages": {}}
    with tempfile.TemporaryDirectory(prefix="guidecast-glossary-") as tmp:
        db = sqlite3.connect(str(pathlib.Path(tmp) / "glossary.db"))
        db.execute("CREATE TABLE terms (src TEXT NOT NULL, lang TEXT NOT NULL, term TEXT NOT NULL, value TEXT NOT NULL, replacement TEXT NOT NULL DEFAULT '', category TEXT NOT NULL, origin TEXT NOT NULL, enabled INTEGER NOT NULL, prefix TEXT NOT NULL, alternatives TEXT NOT NULL, PRIMARY KEY(src,lang,term))")
        for lang, filename in SOURCES.items():
            path = directory / filename
            sheet = xlrd.open_workbook(str(path)).sheet_by_index(0)
            assert sheet.row_values(1) == ["출처", "한글 명칭", "번역어", "분류"]
            grouped = collections.defaultdict(list)
            for row in range(2, sheet.nrows):
                origin, term, value, category = [unicodedata.normalize("NFC", str(x)).strip() for x in sheet.row_values(row)]
                assert term and value, (filename, row + 1)
                # The sheet uses line breaks for sign layout, not spoken word boundaries.
                # Keep originals in alternatives; use single-space speech/search keys.
                grouped[" ".join(term.split())].append({"sourceTerm": term, "value": value, "category": category, "origin": origin, "row": row + 1})
            ambiguous = 0
            for term, variants in sorted(grouped.items()):
                values = sorted(set(" ".join(v["value"].split()) for v in variants))
                ambiguous += len(values) > 1
                db.execute("INSERT INTO terms VALUES(?,?,?,?,?,?,?,?,?,?)", (
                    "ko", lang, term, values[0], "",
                    " / ".join(sorted(set(v["category"] for v in variants))),
                    " / ".join(sorted(set(v["origin"] for v in variants))),
                    int(len(values) == 1), term[:2].lower(),
                    json.dumps(variants, ensure_ascii=False, separators=(",", ":")),
                ))
            report["languages"][lang] = {
                "file": filename, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "rows": sheet.nrows - 2, "uniqueTerms": len(grouped), "ambiguousTerms": ambiguous,
                "maxLengths": [max(len(str(sheet.cell_value(r, c))) for r in range(2, sheet.nrows)) for c in range(4)],
                "origins": dict(collections.Counter(sheet.cell_value(r, 0) for r in range(2, sheet.nrows))),
            }
        db.execute("CREATE INDEX lookup ON terms(src,lang,prefix)")
        db.execute("PRAGMA user_version=1")
        db.commit()
        db.execute("VACUUM")
        db.close()
        data = (pathlib.Path(tmp) / "glossary.db").read_bytes()
        asset = ROOT / "app/src/main/assets/glossary/public-20260905.db.gz"
        asset.parent.mkdir(parents=True, exist_ok=True)
        asset.write_bytes(gzip.compress(data, mtime=0))
        report["databaseBytes"] = len(data)
        report["assetBytes"] = asset.stat().st_size
        report["databaseSha256"] = hashlib.sha256(data).hexdigest()
        (asset.parent / "provenance.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
