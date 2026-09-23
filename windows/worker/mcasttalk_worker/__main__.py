from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from .whisper_cli import ModelSpec, RuntimeSpec, WorkerError, transcribe


def main() -> int:
    parser = argparse.ArgumentParser(description="Bounded offline whisper.cpp CPU transcription")
    parser.add_argument("--executable", required=True, type=Path)
    parser.add_argument("--runtime-sha256", required=True)
    parser.add_argument("--runtime-commit", required=True)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--wav", required=True, type=Path)
    parser.add_argument("--workspace-temp", required=True, type=Path)
    parser.add_argument("--language", required=True)
    parser.add_argument("--backend", default="cpu")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    args = parser.parse_args()
    try:
        result = transcribe(
            RuntimeSpec(args.executable, args.runtime_sha256, args.runtime_commit),
            ModelSpec(args.model, args.model_sha256),
            args.wav,
            args.workspace_temp,
            language=args.language,
            backend=args.backend,
            threads=args.threads,
            timeout_seconds=args.timeout_seconds,
        )
    except WorkerError as error:
        print(json.dumps({"status": "error", "code": error.code, "message": str(error)}), file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print(json.dumps({"status": "error", "code": "CANCELLED"}), file=sys.stderr)
        return 130
    except OSError:
        print(json.dumps({"status": "error", "code": "LOCAL_IO_FAILED"}), file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
