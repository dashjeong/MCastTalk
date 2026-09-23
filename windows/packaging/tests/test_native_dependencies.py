import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("native_dependencies", Path(__file__).resolve().parents[1] / "verify_native_dependencies.py")
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


def pe(dll="KERNEL32.dll", *, delay=False):
    data = bytearray(1024)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3c, 0x80)
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", data, 0x84, 0x8664, 1, 0, 0, 0, 240, 0)
    optional = 0x98
    struct.pack_into("<H", data, optional, 0x20b)
    struct.pack_into("<I", data, optional + 108, 16)
    struct.pack_into("<IIII", data, optional + 240 + 8, 512, 0x1000, 512, 512)
    struct.pack_into("<II", data, optional + 112 + (13 if delay else 1) * 8, 0x1000, 64 if delay else 40)
    if delay:
        struct.pack_into("<II", data, 512, 1, 0x1100)
    else:
        struct.pack_into("<I", data, 512 + 12, 0x1100)
    encoded = dll.encode("ascii") + b"\0"
    data[768:768 + len(encoded)] = encoded
    return data


class NativeDependencyTest(unittest.TestCase):
    def test_normal_import(self):
        self.assertEqual(native.pe_imports(pe()), {"kernel32.dll"})

    def test_delay_import(self):
        self.assertEqual(native.pe_imports(pe("VCOMP140.DLL", delay=True)), {"vcomp140.dll"})

    def test_missing_signature(self):
        with self.assertRaises(ValueError): native.pe_imports(bytes(512))

    def test_truncated_file(self):
        with self.assertRaises(ValueError): native.pe_imports(pe()[:600])

    def test_wrong_architecture(self):
        data = pe(); struct.pack_into("<H", data, 0x84, 0x14c)
        with self.assertRaisesRegex(ValueError, "x64"): native.pe_imports(data)

    def test_unmapped_import_rva(self):
        data = pe(); struct.pack_into("<I", data, 512 + 12, 0xffffffff)
        with self.assertRaises(ValueError): native.pe_imports(data)

    def test_missing_descriptor_terminator(self):
        data = pe(); struct.pack_into("<II", data, 0x98 + 112 + 8, 0x1000, 20)
        with self.assertRaisesRegex(ValueError, "Unterminated"): native.pe_imports(data)

    def test_non_rva_delay_import(self):
        data = pe(delay=True); struct.pack_into("<I", data, 512, 0)
        with self.assertRaisesRegex(ValueError, "non-RVA"): native.pe_imports(data)

    def test_import_path_rejected(self):
        with self.assertRaises(ValueError): native.pe_imports(pe("../evil.dll"))

    def bundle(self, root):
        for name in native.TARGETS:
            file = root / name; file.parent.mkdir(parents=True)
            file.write_bytes(pe("vulkan-1.dll" if "vulkan" in name else "kernel32.dll"))

    def test_static_bundle_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.bundle(root)
            self.assertEqual(len(native.verify(root)["runtimes"]), 3)

    def test_msvc_dependencies_rejected_even_if_present_on_host(self):
        for dll in ("VCOMP140.DLL", "MSVCP140.dll", "VCRUNTIME140.dll", "libstdc++-6.dll"):
            with self.subTest(dll=dll), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); self.bundle(root)
                (root / native.TARGETS[0]).write_bytes(pe(dll))
                with self.assertRaisesRegex(ValueError, "non-system DLL"): native.verify(root)

    def test_delay_loaded_msvc_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.bundle(root)
            (root / native.TARGETS[1]).write_bytes(pe("MSVCP140.dll", delay=True))
            with self.assertRaises(ValueError): native.verify(root)

    def test_cpu_does_not_require_vulkan(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.bundle(root)
            (root / native.TARGETS[1]).write_bytes(pe("vulkan-1.dll"))
            with self.assertRaises(ValueError): native.verify(root)

    def test_legacy_shared_dlls_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); self.bundle(root)
            (root / "whisper/ggml.dll").write_bytes(pe())
            with self.assertRaisesRegex(ValueError, "Unexpected DLLs"): native.verify(root)

    def test_missing_variant_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(OSError): native.verify(Path(folder))


if __name__ == "__main__": unittest.main()
