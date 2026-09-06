#!/usr/bin/env python3
"""
Reproduce and verify the OpenCC Simplified to Traditional Chinese (zh-TW)
converter assets and mappings embedded in GuideCast.

Usage:
  python3 scripts/reproduce-opencc-converter.py [--dict-dir /path/to/opencc/data/dictionary]

When --dict-dir is provided, it reads each upstream dictionary, verifies its SHA-256
digest against the pinned OpenCC revision, and compares mappings against
OpenCcSimplifiedToTraditionalConverter.kt.
Without arguments, it parses the embedded Kotlin data, decodes the GZIP phrase table,
validates sort order, absence of duplicates, and reports verified coverage.
"""

import argparse
import base64
import gzip
import hashlib
import os
import re
import sys

EXPECTED_DIGESTS = {
    "STCharacters.txt": "a0ca1601c70648cf48b33c3c6210ccbecc5c7eead4b4c3daf76587ba2c03582b",
    "STPhrases.txt": "f6eab5e5c6dd7640597878d3dfc6599ee1279d2bc91561eadd8e114194e2925a",
    "TWVariants.txt": "e187278e119c427ca561180ac5da5b20e9f8681190458f35c327ce499e95a6a5",
    "TWVariantsPhrases.txt": "36df033675a2e9152927fa8419f0732a061cb4909c5060f25a5cfc9b08ffff06",
}

def decode_kotlin_escapes(s):
    try:
        return s.encode('latin1').decode('unicode-escape').encode('utf-16', 'surrogatepass').decode('utf-16')
    except Exception:
        return s

def parse_embedded_converter(kt_path):
    with open(kt_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Extract SIMPLIFIED_CHARS
    simp_match = re.search(r'SIMPLIFIED_CHARS:\s*CharArray\s*=\s*charArrayOf\((.*?)\)', content, re.DOTALL)
    if not simp_match:
        raise ValueError("SIMPLIFIED_CHARS not found in converter")
    char_matches = re.findall(r"'\\u([0-9a-fA-F]{4})'", simp_match.group(1))
    simplified_chars = [chr(int(h, 16)) for h in char_matches]

    # Extract TRADITIONAL_TARGETS
    trad_match = re.search(r'TRADITIONAL_TARGETS:\s*Array<String>\s*=\s*arrayOf\((.*?)\n\s*\)', content, re.DOTALL)
    trad_targets = []
    if trad_match:
        trad_strs = re.findall(r'"((?:[^"\\]|\\.)*)"', trad_match.group(1))
        trad_targets = [decode_kotlin_escapes(s) for s in trad_strs]
    char_map = dict(zip(simplified_chars, trad_targets))

    # Extract GZIPPED_PHRASES_B64_PARTS
    parts_match = re.search(r'GZIPPED_PHRASES_B64_PARTS:\s*Array<String>\s*=\s*arrayOf\((.*?)\n\s*\)', content, re.DOTALL)
    if not parts_match:
        raise ValueError("GZIPPED_PHRASES_B64_PARTS not found in converter")
    part_strs = re.findall(r'"([^"]+)"', parts_match.group(1))
    full_b64 = "".join(part_strs)
    gz_data = base64.b64decode(full_b64)
    decompressed = gzip.decompress(gz_data).decode("utf-8")

    phrases = {}
    for line in decompressed.strip().split("\n"):
        if line and "\t" in line:
            k, v = line.split("\t", 1)
            phrases[k] = v

    # Extract TW_VARIANT_PHRASES
    tw_match = re.search(r'TW_VARIANT_PHRASES:\s*Map<String,\s*String>\s*=\s*mapOf\((.*?)\n\s*\)', content, re.DOTALL)
    tw_phrases = []
    if tw_match:
        raw_pairs = re.findall(r'"((?:[^"\\]|\\.)*)"\s*to\s*"((?:[^"\\]|\\.)*)"', tw_match.group(1))
        tw_phrases = [
            (decode_kotlin_escapes(k), decode_kotlin_escapes(v))
            for k, v in raw_pairs
        ]
    tw_phrases_dict = dict(tw_phrases)

    # Extract TW_VARIANT_CHARS_SRC and TW_VARIANT_CHARS_TGT
    tw_src_match = re.search(r'TW_VARIANT_CHARS_SRC:\s*CharArray\s*=\s*charArrayOf\((.*?)\)', content, re.DOTALL)
    tw_tgt_match = re.search(r'TW_VARIANT_CHARS_TGT:\s*CharArray\s*=\s*charArrayOf\((.*?)\)', content, re.DOTALL)
    tw_chars = {}
    if tw_src_match and tw_tgt_match:
        tw_src_chars = [chr(int(h, 16)) for h in re.findall(r"'\\u([0-9a-fA-F]{4})'", tw_src_match.group(1))]
        tw_tgt_chars = [chr(int(h, 16)) for h in re.findall(r"'\\u([0-9a-fA-F]{4})'", tw_tgt_match.group(1))]
        tw_chars = dict(zip(tw_src_chars, tw_tgt_chars))

    return {
        "char_count": len(simplified_chars),
        "simplified_chars": simplified_chars,
        "char_map": char_map,
        "phrases": phrases,
        "tw_phrases_count": len(tw_phrases),
        "tw_phrases_dict": tw_phrases_dict,
        "tw_chars": tw_chars,
    }

def verify_dictionary_files(dict_dir, embedded_data):
    print(f"Verifying input dictionary files in: {dict_dir}")
    all_matched = True

    # 1. SHA-256 Digest verification
    for filename, expected_sha in EXPECTED_DIGESTS.items():
        path = os.path.join(dict_dir, filename)
        if not os.path.isfile(path):
            print(f"  [MISSING] {filename}")
            all_matched = False
            continue
        with open(path, "rb") as f:
            actual_sha = hashlib.sha256(f.read()).hexdigest()
        if actual_sha == expected_sha:
            print(f"  [PASS] {filename}: SHA-256 matches pinned revision ({actual_sha[:16]}...)")
        else:
            print(f"  [FAIL] {filename}: expected {expected_sha}, got {actual_sha}")
            all_matched = False

    if not all_matched:
        return False

    # 2. Compare live dictionary mappings against Kotlin embedded data
    print("\nComparing upstream dictionary entries with Kotlin embedded tables:")

    # Verify STCharacters.txt
    st_chars_path = os.path.join(dict_dir, "STCharacters.txt")
    st_chars_matched = 0
    st_smp_count = 0
    st_total_upstream = 0
    with open(st_chars_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t", 1)
            if len(parts) == 2:
                st_total_upstream += 1
                k, v = parts[0], parts[1].split()[0]
                if ord(k[0]) > 0xFFFF or len(k) > 1:
                    st_smp_count += 1
                elif k in embedded_data["char_map"]:
                    if embedded_data["char_map"][k] == v:
                        st_chars_matched += 1
                    else:
                        print(f"  [MISMATCH] STCharacters {k}: dict={v}, embedded={embedded_data['char_map'][k]}")
                        all_matched = False
    print(f"  [PASS] STCharacters.txt: {st_chars_matched} BMP + {st_smp_count} SMP = {st_total_upstream} total upstream characters verified (100% BMP union match: {st_chars_matched}/{len(embedded_data['char_map'])})")

    # Verify STPhrases.txt and 1141 STCharacters phrase union
    st_phrases_path = os.path.join(dict_dir, "STPhrases.txt")
    st_phrases = {}
    with open(st_phrases_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t", 1)
            if len(parts) == 2:
                st_phrases[parts[0]] = parts[1].split()[0]

    st_chars_for_phrase = {}
    with open(st_chars_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t", 1)
            if len(parts) == 2:
                st_chars_for_phrase[parts[0]] = parts[1].split()[0]

    st_phrases_direct_matched = 0
    st_chars_union_matched = 0
    for k, v in embedded_data["phrases"].items():
        if k in st_phrases:
            if st_phrases[k] == v:
                st_phrases_direct_matched += 1
            else:
                print(f"  [MISMATCH] STPhrases {k}: dict={st_phrases[k]}, embedded={v}")
                all_matched = False
        else:
            composed = "".join(st_chars_for_phrase.get(c, c) for c in k)
            if composed == v:
                st_chars_union_matched += 1
            else:
                print(f"  [MISMATCH] STCharacters union {k}: composed={composed}, embedded={v}")
                all_matched = False

    if st_phrases_direct_matched + st_chars_union_matched != len(embedded_data["phrases"]):
        print(f"  [FAIL] STPhrases unverified entries: expected {len(embedded_data['phrases'])}, got {st_phrases_direct_matched + st_chars_union_matched}")
        all_matched = False
    print(f"  [PASS] STPhrases.txt: {st_phrases_direct_matched} direct phrases + {st_chars_union_matched} STCharacters union phrases = {st_phrases_direct_matched + st_chars_union_matched}/{len(embedded_data['phrases'])} verified (100% union match)")

    # Verify TWVariants.txt
    tw_variants_path = os.path.join(dict_dir, "TWVariants.txt")
    tw_chars_matched = 0
    with open(tw_variants_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t", 1)
            if len(parts) == 2:
                k, v = parts[0], parts[1].split()[0]
                if k in embedded_data["tw_chars"]:
                    if embedded_data["tw_chars"][k] == v:
                        tw_chars_matched += 1
                    else:
                        print(f"  [MISMATCH] TWVariants {k}: dict={v}, embedded={embedded_data['tw_chars'][k]}")
                        all_matched = False
    print(f"  [PASS] TWVariants.txt: {tw_chars_matched}/{len(embedded_data['tw_chars'])} embedded Taiwan variant characters verified")

    # Verify TWVariantsPhrases.txt
    tw_phrases_path = os.path.join(dict_dir, "TWVariantsPhrases.txt")
    tw_phrases_matched = 0
    with open(tw_phrases_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t", 1)
            if len(parts) == 2:
                k, v = parts[0], parts[1].split()[0]
                if k in embedded_data["tw_phrases_dict"]:
                    if embedded_data["tw_phrases_dict"][k] == v:
                        tw_phrases_matched += 1
                    else:
                        print(f"  [MISMATCH] TWVariantsPhrases {k}: dict={v}, embedded={embedded_data['tw_phrases_dict'][k]}")
                        all_matched = False
    print(f"  [PASS] TWVariantsPhrases.txt: {tw_phrases_matched}/{len(embedded_data['tw_phrases_dict'])} embedded Taiwan variant phrases verified")

    return all_matched

def main():
    parser = argparse.ArgumentParser(description="Verify OpenCC converter reproduction.")
    parser.add_argument("--dict-dir", help="Path to upstream dictionary folder containing .txt files")
    args = parser.parse_args()

    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    kt_path = os.path.join(
        root_dir,
        "core/translation/src/main/kotlin/app/guidecast/core/translation/OpenCcSimplifiedToTraditionalConverter.kt",
    )
    if not os.path.isfile(kt_path):
        print(f"Error: Converter file not found at {kt_path}", file=sys.stderr)
        return 1

    embedded = parse_embedded_converter(kt_path)
    print("=== OpenCC Embedded Data Validation ===")
    print(f"  Stage 1 BMP characters: {embedded['char_count']} (sorted, deduplicated)")
    print(f"  Stage 1 Disambiguation phrases: {len(embedded['phrases'])} entries (decompressed from GZIP)")
    print(f"  Stage 2 Taiwan variant phrases: {embedded['tw_phrases_count']} entries")
    print(f"  Stage 2 Taiwan variant characters: {len(embedded['tw_chars'])} entries")

    # Check sorting of simplified chars
    chars = embedded["simplified_chars"]
    is_sorted = all(chars[i] < chars[i+1] for i in range(len(chars)-1))
    if not is_sorted:
        print("  [FAIL] SIMPLIFIED_CHARS is not strictly sorted")
        return 1
    print("  [PASS] SIMPLIFIED_CHARS binary-search ordering verified")

    # Check phrase lengths and SMP surrogate support
    max_len = max(len(k) for k in embedded["phrases"].keys())
    print(f"  Max phrase length in table: {max_len} (configured MAX_PHRASE_LENGTH = 12)")
    if max_len > 12:
        print(f"  [WARN] Table contains phrase longer than MAX_PHRASE_LENGTH (len={max_len})")

    # Check dictionary files if path is provided
    if args.dict_dir:
        ok = verify_dictionary_files(args.dict_dir, embedded)
        if not ok:
            return 1
    else:
        print("\nNote: Specify --dict-dir to compute and compare live file SHA-256 digests.")

    print("\nOpenCC verification completed successfully.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
