from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Sequence

from .benchmark import BenchmarkError, summarize_benchmark
from .hardware import probe_hardware
from .manifest import verify_model_pack
from .paths import DataRootError, initialize_data_root, resolve_data_root


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="mcasttalk-bootstrap",
        description="Offline Windows bootstrap for MCastTalk",
    )
    subcommands = parser.add_subparsers(dest="command", required=True)

    initialize = subcommands.add_parser(
        "init-data",
        help="Create and validate the MCastTalkData directory",
    )
    initialize.add_argument("--data-dir", help="Selected MCastTalkData path")
    initialize.add_argument(
        "--executable-dir",
        help="Executable directory used for the default path",
    )

    subcommands.add_parser(
        "probe-hardware",
        help="Print the detected hardware profile as JSON",
    )

    verify = subcommands.add_parser(
        "verify-pack",
        help="Verify an offline model pack without installing it",
    )
    verify.add_argument("package_dir", help="Directory containing model-pack.json")
    summarize = subcommands.add_parser(
        "summarize-benchmark",
        help="Validate and summarize offline benchmark evidence",
    )
    summarize.add_argument("evidence", help="Benchmark evidence JSON file")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    if arguments.command == "init-data":
        executable_dir = (
            Path(arguments.executable_dir).expanduser().resolve()
            if arguments.executable_dir
            else None
        )
        try:
            root = resolve_data_root(arguments.data_dir, executable_dir)
            layout = initialize_data_root(root)
        except DataRootError as exc:
            print(
                json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False),
                file=sys.stderr,
            )
            return 2
        print(
            json.dumps({"ok": True, **layout.as_dict()}, ensure_ascii=False, indent=2)
        )
        return 0

    if arguments.command == "probe-hardware":
        print(probe_hardware().to_json())
        return 0

    if arguments.command == "verify-pack":
        verification = verify_model_pack(Path(arguments.package_dir))
        print(json.dumps(verification.as_dict(), ensure_ascii=False, indent=2))
        return 0 if verification.accepted else 3

    if arguments.command == "summarize-benchmark":
        try:
            summary = summarize_benchmark(Path(arguments.evidence))
        except BenchmarkError as exc:
            print(
                json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False),
                file=sys.stderr,
            )
            return 4
        print(
            json.dumps({"ok": True, **summary.as_dict()}, ensure_ascii=False, indent=2)
        )
        return 0

    return 1
