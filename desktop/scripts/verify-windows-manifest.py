"""Read-only stdlib PE/.rsrc verifier. No process, download or socket operations."""
import argparse
import hashlib
import json
import pathlib
import struct
import xml.etree.ElementTree as ET

MAX_PE = 128 << 20
MAX_MANIFEST = 64 << 10
ARCH = {0x8664: 'amd64', 0xaa64: 'arm64'}
ASM1 = 'urn:schemas-microsoft-com:asm.v1'
ASM3 = 'urn:schemas-microsoft-com:asm.v3'
COMPAT = 'urn:schemas-microsoft-com:compatibility.v1'
SETTINGS2019 = 'http://schemas.microsoft.com/SMI/2019/WindowsSettings'
WIN10 = '{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}'

def require(condition, code):
    if not condition:
        raise ValueError(code)

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def read(path, maximum):
    path = pathlib.Path(path)
    require(path.is_file() and 0 < path.stat().st_size <= maximum, 'FILE_SIZE_OR_TYPE_REJECTED')
    with path.open('rb') as f:
        raw = f.read(maximum + 1)
    require(len(raw) <= maximum, 'FILE_SIZE_REJECTED')
    return raw

def manifest_xml(raw):
    require(0 < len(raw) <= MAX_MANIFEST, 'MANIFEST_SIZE_REJECTED')
    text = raw.decode('utf-8', errors='strict')
    require('<!DOCTYPE' not in text.upper() and '<!ENTITY' not in text.upper(), 'MANIFEST_DTD_REJECTED')
    require('encoding="UTF-8"' in text[:200] or "encoding='UTF-8'" in text[:200], 'XML_UTF8_DECLARATION_MISSING')
    root = ET.fromstring(text)
    require(root.tag == '{%s}assembly' % ASM1 and root.attrib == {'manifestVersion': '1.0'}, 'ASSEMBLY_ROOT_REJECTED')
    identities = root.findall('{%s}assemblyIdentity' % ASM1)
    require(len(identities) == 1, 'ASSEMBLY_IDENTITY_AMBIGUOUS')
    identity = identities[0].attrib
    require(identity == {'version': '0.2.48.0', 'processorArchitecture': '*', 'name': 'MCastTalk.Desktop', 'type': 'win32'}, 'ASSEMBLY_IDENTITY_REJECTED')
    execution = root.findall('.//{%s}requestedExecutionLevel' % ASM3)
    require(len(execution) == 1 and execution[0].attrib == {'level': 'asInvoker', 'uiAccess': 'false'}, 'EXECUTION_PRIVILEGE_REJECTED')
    active = root.findall('{%s}application/{%s}windowsSettings/{%s}activeCodePage' % (ASM3, ASM3, SETTINGS2019))
    require(len(active) == 1 and active[0].text is not None and active[0].text.strip() == 'UTF-8' and not active[0].attrib, 'ACTIVE_CODEPAGE_REJECTED')
    require(len(root.findall('.//{%s}activeCodePage' % SETTINGS2019)) == 1, 'ACTIVE_CODEPAGE_AMBIGUOUS')
    supported = root.findall('{%s}compatibility/{%s}application/{%s}supportedOS' % (COMPAT, COMPAT, COMPAT))
    require(len(supported) == 1 and supported[0].attrib == {'Id': WIN10}, 'WINDOWS_COMPATIBILITY_REJECTED')
    # Fail closed on unexpected elements/attributes: no added privilege, locale,
    # codepage or external dependentAssembly directives hidden in the manifest.
    allowed = {
        '{%s}%s' % (ASM1, name) for name in ['assembly', 'assemblyIdentity', 'description']
    } | {'{%s}%s' % (ASM3, name) for name in ['trustInfo', 'security', 'requestedPrivileges', 'requestedExecutionLevel', 'application', 'windowsSettings']} | {
        '{%s}%s' % (COMPAT, name) for name in ['compatibility', 'application', 'supportedOS']
    } | {'{%s}activeCodePage' % SETTINGS2019}
    require(all(e.tag in allowed for e in root.iter()), 'UNEXPECTED_MANIFEST_ELEMENT')
    return {'sha256': digest(raw), 'bytes': len(raw), 'xmlEncoding': 'UTF-8',
            'activeCodePage': 'UTF-8', 'requestedExecutionLevel': 'asInvoker',
            'uiAccess': False, 'supportedOSGUID': WIN10,
            'systemLocaleChanged': False, 'requestsElevation': False}

def u16(data, off):
    require(0 <= off <= len(data) - 2, 'BINARY_U16_OUT_OF_BOUNDS')
    return struct.unpack_from('<H', data, off)[0]

def u32(data, off):
    require(0 <= off <= len(data) - 4, 'BINARY_U32_OUT_OF_BOUNDS')
    return struct.unpack_from('<I', data, off)[0]

def sections(data, off, count):
    require(0 < count <= 96 and off + count * 40 <= len(data), 'SECTION_TABLE_REJECTED')
    result = []
    for i in range(count):
        entry = off + i * 40
        name = data[entry:entry + 8].rstrip(b'\0').decode('ascii', errors='strict')
        virtual_size, virtual_address, raw_size, raw_offset = struct.unpack_from('<IIII', data, entry + 8)
        require(raw_offset + raw_size <= len(data), 'SECTION_RAW_RANGE_REJECTED')
        result.append({'name': name, 'virtualSize': virtual_size, 'rva': virtual_address,
                       'rawSize': raw_size, 'rawOffset': raw_offset, 'headerOffset': entry})
    return result

def inspect_pe(raw, expected_manifest, expected_arch=None, expected_lang=0x409):
    require(len(raw) <= MAX_PE and len(raw) >= 64 and raw[:2] == b'MZ', 'NOT_DOS_PE_FILE')
    pe = u32(raw, 0x3c)
    require(pe >= 64 and pe + 24 <= len(raw) and raw[pe:pe + 4] == b'PE\0\0', 'PE_SIGNATURE_REJECTED')
    machine, count = u16(raw, pe + 4), u16(raw, pe + 6)
    require(machine in ARCH and (expected_arch is None or ARCH[machine] == expected_arch), 'PE_ARCH_REJECTED')
    optional_bytes = u16(raw, pe + 20)
    optional = pe + 24
    require(optional_bytes >= 136 and optional + optional_bytes <= len(raw) and u16(raw, optional) == 0x20b, 'PE32_PLUS_HEADER_REJECTED')
    require(u32(raw, optional + 108) >= 3, 'RESOURCE_DATA_DIRECTORY_MISSING')
    resource_rva, resource_size = u32(raw, optional + 128), u32(raw, optional + 132)
    require(resource_rva != 0 and 32 <= resource_size <= 8 << 20, 'RESOURCE_RVA_SIZE_REJECTED')
    sec = sections(raw, optional + optional_bytes, count)

    def map_rva(rva, length):
        require(length >= 0 and rva <= 0xffffffff - length, 'RVA_LENGTH_OVERFLOW')
        matches = [s for s in sec if s['rva'] <= rva and rva + length <= s['rva'] + s['rawSize']]
        require(len(matches) == 1, 'RVA_NOT_UNIQUELY_FILE_BACKED')
        s = matches[0]
        offset = s['rawOffset'] + rva - s['rva']
        require(offset + length <= len(raw), 'RVA_FILE_RANGE_REJECTED')
        return offset, s

    resource_start, resource_section = map_rva(resource_rva, resource_size)
    require(resource_section['name'] == '.rsrc', 'RESOURCE_SECTION_NAME_REJECTED')
    directory = raw[resource_start:resource_start + resource_size]

    def entries(rel):
        require(0 <= rel <= len(directory) - 16, 'RESOURCE_DIRECTORY_OFFSET_REJECTED')
        count = u16(directory, rel + 12) + u16(directory, rel + 14)
        require(0 < count <= 4096 and rel + 16 + count * 8 <= len(directory), 'RESOURCE_ENTRY_COUNT_REJECTED')
        return [(u32(directory, rel + 16 + i * 8), u32(directory, rel + 20 + i * 8)) for i in range(count)]

    def numeric_child(rel, wanted, needs_directory):
        matches = [(name, value) for name, value in entries(rel) if name == wanted]
        require(len(matches) == 1, 'RESOURCE_ID_MISSING_OR_AMBIGUOUS')
        value = matches[0][1]
        require(bool(value & 0x80000000) == needs_directory, 'RESOURCE_TREE_LEVEL_REJECTED')
        return value & 0x7fffffff

    type_dir = numeric_child(0, 24, True)  # RT_MANIFEST, never a binary string scan.
    manifest_ids = entries(type_dir)
    require(len(manifest_ids) == 1 and manifest_ids[0][0] == 1, 'APPLICATION_MANIFEST_ID_NOT_EXACTLY_ONE')
    id_dir = numeric_child(type_dir, 1, True)
    languages = entries(id_dir)
    require(len(languages) == 1, 'MANIFEST_LANGUAGE_AMBIGUOUS')
    lang, leaf = languages[0]
    require(lang == expected_lang and not leaf & 0x80000000, 'MANIFEST_LANGUAGE_OR_LEAF_REJECTED')
    require(leaf <= len(directory) - 16, 'RESOURCE_DATA_ENTRY_OUT_OF_BOUNDS')
    payload_rva, payload_bytes, codepage, reserved = struct.unpack_from('<IIII', directory, leaf)
    require(0 < payload_bytes <= MAX_MANIFEST and reserved == 0 and codepage in (0, 65001), 'MANIFEST_DATA_ENTRY_REJECTED')
    payload_offset, payload_section = map_rva(payload_rva, payload_bytes)
    require(payload_section is resource_section and resource_start <= payload_offset and payload_offset + payload_bytes <= resource_start + resource_size, 'MANIFEST_PAYLOAD_NOT_IN_RSRC_DIRECTORY')
    payload = raw[payload_offset:payload_offset + payload_bytes]
    require(payload == expected_manifest, 'EMBEDDED_MANIFEST_DIFFERS_FROM_FIXED_SOURCE')
    xml = manifest_xml(payload)
    return {'state': 'PE_RESOURCE_VERIFIED', 'bytes': len(raw), 'sha256': digest(raw),
            'machine': hex(machine), 'architecture': ARCH[machine], 'resourceSection': '.rsrc',
            'resourceType': 24, 'resourceID': 1, 'resourceLanguageID': hex(lang),
            'resourceLanguage': 'en-US' if lang == 0x409 else 'explicit expected LANGID',
            'resourceDataCodePage': codepage,
            'resourceDataCodePageMeaning': 'UTF-8' if codepage == 65001 else 'unspecified; strict UTF-8 XML encoding verified',
            'processCodePageRequested': 65001, 'manifest': xml,
            'actualWindowsCodePageMeasured': False, 'UnicodeTTSFixVerified': False}

def inspect_coff(raw):
    require(20 <= len(raw) <= 16 << 20 and raw[:2] != b'MZ', 'NOT_BOUNDED_COFF_OBJECT')
    machine, count = u16(raw, 0), u16(raw, 2)
    require(machine in ARCH and u16(raw, 16) == 0, 'COFF_ARCH_OR_OPTIONAL_HEADER_REJECTED')
    secs = sections(raw, 20, count)
    resource = [s for s in secs if s['name'].startswith('.rsrc')]
    require(resource, 'COFF_RESOURCE_SECTION_MISSING')
    for s in resource:
        relocation_offset = u32(raw, s['headerOffset'] + 24)
        relocations = u16(raw, s['headerOffset'] + 32)
        require(relocations < 0xffff and relocation_offset + relocations * 10 <= len(raw), 'COFF_RELOCATION_RANGE_REJECTED')
    return {'state': 'COFF_HEADER_AND_SHA_RECORDED', 'sha256': digest(raw), 'bytes': len(raw),
            'machine': hex(machine), 'architecture': ARCH[machine],
            'resourceSections': [s['name'] for s in resource],
            'relocatedManifestConfirmed': False, 'scope': 'Final linked PE resource must be verified separately.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--exe', action='append', default=[])
    parser.add_argument('--coff', action='append', default=[])
    parser.add_argument('--expect-arch', choices=['amd64', 'arm64'])
    parser.add_argument('--expect-lang', type=lambda x: int(x, 0), default=0x409)
    parser.add_argument('--out')
    args = parser.parse_args()
    report = {'author': 'Windows executable resource inspection', 'runtimeTestPerformed': False,
              'WindowsExecuted': False, 'UnicodeFixAccepted': False, 'executables': [], 'coffObjects': []}
    exit_code = 0
    try:
        manifest = read(args.manifest, MAX_MANIFEST)
        report['sourceManifest'] = dict(manifest_xml(manifest), path=str(pathlib.Path(args.manifest).resolve()))
        for path in args.exe:
            report['executables'].append(dict(inspect_pe(read(path, MAX_PE), manifest, args.expect_arch, args.expect_lang), path=str(pathlib.Path(path).resolve())))
        for path in args.coff:
            report['coffObjects'].append(dict(inspect_coff(read(path, 16 << 20)), path=str(pathlib.Path(path).resolve())))
        report['state'] = 'PASS_PE_RESOURCES' if args.exe else 'SOURCE_ONLY_NO_LINKED_PE_PROVIDED'
    except (ValueError, OSError, ET.ParseError, UnicodeError, struct.error) as error:
        report['state'] = 'FAILED'
        report['errorClass'] = type(error).__name__
        report['error'] = str(error)[:200]
        exit_code = 1
    raw = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.out:
        pathlib.Path(args.out).write_text(raw)
    print(raw)
    return exit_code

if __name__ == '__main__':
    raise SystemExit(main())
