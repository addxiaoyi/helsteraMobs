#!/usr/bin/env python3
"""Audit Paper evidence history and emit a sanitized Git blob replacement plan.

This command never changes Git refs or pushes. Apply a reviewed plan only to an
isolated clone, verify the result, and use an explicit force-with-lease to publish.
"""

from __future__ import annotations

import argparse
import base64
import copy
import gzip
import hashlib
import io
import json
import re
import subprocess
import tarfile
import zipfile
from pathlib import Path

REPORT_PREFIX = "tools/paper-integration/reports/"
PATTERNS = (
    re.compile(rb"""(?i)([?&]token=)([^&\s"'<>]+)"""),
    re.compile(rb"""(?i)(__cf_bm=)([^;\s"'<>]+)"""),
    re.compile(rb"""(?i)(\bauthorization\s*:\s*bearer\s+)([^\s"'<>]+)"""),
    re.compile(r"(令牌[ \t]*(?::|：)[ \t]*)([A-Za-z0-9._~-]+)".encode()),
)
SHA256 = re.compile(rb"\b[0-9a-fA-F]{64}\b")
EVIDENCE_SUFFIXES = {".gz", ".zip", ".json", ".txt", ".log"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def redact_text(data):
    count = 0

    def replace(match):
        nonlocal count
        if match.group(2).lower() == b"[redacted]":
            return match.group(0)
        count += 1
        return match.group(1) + b"[REDACTED]"

    for pattern in PATTERNS:
        data = pattern.sub(replace, data)
    return data, count


def sanitize_blob(data, depth=0):
    """Return sanitized bytes, match count and changed container/member hashes."""
    if depth > 8:
        raise ValueError("Evidence archive nesting exceeds eight levels")
    hashes = {}
    if data.startswith(b"\x1f\x8b"):
        raw = gzip.decompress(data)
        cleaned, count, hashes = sanitize_blob(raw, depth + 1)
        result = gzip.compress(cleaned, mtime=0) if count else data
    elif data.startswith(b"PK\x03\x04"):
        target = io.BytesIO()
        count = 0
        with zipfile.ZipFile(io.BytesIO(data)) as source:
            with zipfile.ZipFile(target, "w") as output:
                output.comment = source.comment
                for info in source.infolist():
                    raw = source.read(info)
                    cleaned, hits, member_hashes = sanitize_blob(raw, depth + 1)
                    count += hits
                    hashes.update(member_hashes)
                    output.writestr(info, cleaned)
        result = target.getvalue() if count else data
    elif len(data) >= 512 and data[257:262] == b"ustar":
        target = io.BytesIO()
        count = 0
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as source:
            with tarfile.open(fileobj=target, mode="w:", format=tarfile.PAX_FORMAT,
                              pax_headers=source.pax_headers) as output:
                for info in source:
                    cloned = copy.copy(info)
                    if not info.isfile():
                        output.addfile(cloned)
                        continue
                    with source.extractfile(info) as stream:
                        raw = stream.read()
                    cleaned, hits, member_hashes = sanitize_blob(raw, depth + 1)
                    count += hits
                    hashes.update(member_hashes)
                    cloned.size = len(cleaned)
                    cloned.pax_headers = dict(info.pax_headers)
                    cloned.pax_headers.pop("size", None)
                    output.addfile(cloned, io.BytesIO(cleaned))
        result = target.getvalue() if count else data
    else:
        result, count = redact_text(data)
    if result != data:
        hashes[digest(data)] = digest(result)
    return result, count, hashes


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root)


def read_blobs(root, ref="HEAD", current=False):
    if current:
        objects = []
        for entry in git(root, "ls-tree", "-r", "-z", ref).split(b"\0"):
            if not entry:
                continue
            header, name = entry.split(b"\t", 1)
            _, kind, oid = header.split()
            if kind == b"blob":
                objects.append((oid.decode(), name.decode()))
    else:
        objects = [tuple(line.split(" ", 1))
                   for line in git(root, "rev-list", "--objects", ref).decode().splitlines()
                   if " " in line]
    # Source code, fixtures and unrelated binary assets must not be rewritten.
    selected = {oid: name for oid, name in objects
                if (name.startswith(REPORT_PREFIX) and Path(name).suffix in EVIDENCE_SUFFIXES)
                or Path(name).suffix.lower() == ".md"}
    entries = list(selected.items())
    blobs = {}
    # communicate() reads and writes together; filling both batch pipes deadlocks.
    for offset in range(0, len(entries), 24):
        batch = entries[offset:offset + 24]
        process = subprocess.run(["git", "cat-file", "--batch"], cwd=root,
                                 input=("\n".join(oid for oid, _ in batch) + "\n").encode(),
                                 stdout=subprocess.PIPE, check=True)
        data = process.stdout
        position = 0
        for oid, name in batch:
            end = data.index(b"\n", position)
            actual, kind, length = data[position:end].split()
            if actual.decode() != oid or kind != b"blob":
                raise ValueError(f"Expected evidence blob {oid}")
            position = end + 1
            size = int(length)
            blobs[oid] = (name, data[position:position + size])
            position += size + 1
    return blobs


def prepare(blobs):
    base = {}
    checksums = {}
    occurrences = 0
    credentials = {}
    for oid, (name, data) in blobs.items():
        cleaned, count, hashes = (sanitize_blob(data) if name.startswith(REPORT_PREFIX)
                                  else (data, 0, {}))
        base[oid] = cleaned
        checksums.update(hashes)
        if count:
            credentials[oid] = count
            occurrences += count
    # Always recompute from original sanitized text, not a partially rewritten
    # intermediate. A report's checksum may be referenced in another document.
    updates = {}
    for _ in range(32):
        changed = False
        for oid, (name, original) in blobs.items():
            data = base[oid]
            if Path(name).suffix in {".gz", ".zip"}:
                cleaned = data
            else:
                cleaned = SHA256.sub(
                    lambda match: checksums.get(match.group().decode().lower(),
                                                match.group().decode()).encode(), data)
            if cleaned != original:
                updates[oid] = cleaned
                old_hash, new_hash = digest(original), digest(cleaned)
                if checksums.get(old_hash) != new_hash:
                    checksums[old_hash] = new_hash
                    changed = True
        if not changed:
            return updates, occurrences, credentials
    raise ValueError("Checksum dependency graph did not converge")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--current", action="store_true", help="audit only the selected commit")
    parser.add_argument("--write-plan", type=Path, help="write sanitized replacements; no ref changes")
    parser.add_argument("--check", action="store_true", help="exit nonzero if redaction is needed")
    args = parser.parse_args()
    root = args.root.resolve()
    head = git(root, "rev-parse", args.ref + "^{commit}").decode().strip()
    blobs = read_blobs(root, head, args.current)
    updates, occurrences, credentials = prepare(blobs)
    print(f"Scanned {len(blobs)} evidence/document blob versions; "
          f"{len(credentials)} credential-bearing versions, {occurrences} occurrences; "
          f"{len(updates)} total replacements including checksum references.")
    if args.write_plan:
        plan = {
            "schemaVersion": 1, "originalHead": head,
            "originalTree": git(root, "rev-parse", head + "^{tree}").decode().strip(),
            "scannedBlobCount": len(blobs), "credentialOccurrences": occurrences,
            "replacements": [
                {"originalOid": oid, "path": blobs[oid][0],
                 "sha256Before": digest(blobs[oid][1]), "sha256After": digest(data),
                 "credentialOccurrences": credentials.get(oid, 0),
                 "dataBase64": base64.b64encode(data).decode()}
                for oid, data in sorted(updates.items())
            ],
        }
        with args.write_plan.open("x", encoding="utf-8") as output:
            json.dump(plan, output, indent=2)
            output.write("\n")
        print(f"Sanitized replacement plan written to {args.write_plan}")
    return 1 if args.check and occurrences else 0


if __name__ == "__main__":
    raise SystemExit(main())
