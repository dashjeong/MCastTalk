# Open Chinese Convert (OpenCC)

OpenCC is an open source project for conversions between Traditional Chinese
and Simplified Chinese, supporting character-level and phrase-level conversion
with context-sensitive disambiguation.

- **Upstream:** <https://github.com/BYVoid/OpenCC>
- **Pinned Revision:** `26753884f1984add422f3b0249ccee8613deaff6`
- **License:** Apache-2.0 (see `OPENCC-APACHE-2.0.txt`)
- **Copyright:** Copyright (c) 2010-2023 BYVoid <byvoid@byvoid.com>

Packaged Derivative:
GuideCast includes an offline, zero-dependency Simplified to Traditional Chinese
(Taiwan Standard, zh-TW) converter derived from OpenCC standard s2tw.json:
- `STCharacters.txt` (SHA-256: `a0ca1601c70648cf48b33c3c6210ccbecc5c7eead4b4c3daf76587ba2c03582b`)
- `STPhrases.txt` (SHA-256: `f6eab5e5c6dd7640597878d3dfc6599ee1279d2bc91561eadd8e114194e2925a`)
- `TWVariants.txt` (SHA-256: `e187278e119c427ca561180ac5da5b20e9f8681190458f35c327ce499e95a6a5`)
- `TWVariantsPhrases.txt` (SHA-256: `36df033675a2e9152927fa8419f0732a061cb4909c5060f25a5cfc9b08ffff06`)

Verification and reproduction tooling is provided in `scripts/reproduce-opencc-converter.py`.
Coverage:
- 2,871 Unicode BMP characters in Stage 1 fallback table (100% match of BMP entries in `STCharacters.txt`)
- 1,141 Unicode SMP characters (U+10000+, CJK Extension B-F) in upstream `STCharacters.txt` (preserved as pass-through)
- Union total: 2,871 BMP + 1,141 SMP = 4,012 characters in OpenCC `STCharacters.txt`
- 10,937 multi-character disambiguation phrases in Stage 1
- 12 Taiwan standard variant phrases and 41 character mappings in Stage 2
