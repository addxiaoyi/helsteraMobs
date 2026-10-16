"""Validate a generated Paper integration report against its JSON schema."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: validate-report.py <schema.json> <report.json>", file=sys.stderr)
        return 2
    schema_path, report_path = map(Path, sys.argv[1:])
    schema = json.loads(schema_path.read_text(encoding="utf-8-sig"))
    report = json.loads(report_path.read_text(encoding="utf-8-sig"))
    errors = sorted(Draft202012Validator(schema).iter_errors(report), key=lambda e: list(e.path))
    if errors:
        for error in errors:
            path = ".".join(str(part) for part in error.path) or "$"
            print(f"{path}: {error.message}", file=sys.stderr)
        return 1
    print("Paper integration report schema valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
