"""Credential redaction tests, including historical archives and checksum chains."""

import gzip
import hashlib
import importlib.util
import io
import json
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "redactor", Path(__file__).with_name("redact-archived-logs.py"))
redactor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(redactor)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class RedactArchivedLogsTest(unittest.TestCase):
    def test_query_cookie_bearer_and_chinese_label_without_touching_selector_tokens(self):
        data = ("http://localhost/?token=secret&view=home __cf_bm=cookie; Path=/ "
                "Authorization: Bearer credential 令牌： abc123 token=followpath").encode()
        cleaned, count = redactor.redact_text(data)
        self.assertEqual(count, 4)
        self.assertEqual(cleaned, (
            "http://localhost/?token=[REDACTED]&view=home __cf_bm=[REDACTED]; Path=/ "
            "Authorization: Bearer [REDACTED] 令牌： [REDACTED] token=followpath").encode())
        self.assertEqual(redactor.redact_text(cleaned), (cleaned, 0))

    def test_gzip_is_deterministic_and_already_redacted_archive_is_byte_identical(self):
        source = gzip.compress(b"http://localhost/?token=secret\n", mtime=42)
        clean, count, checksums = redactor.sanitize_blob(source)
        self.assertEqual(count, 1)
        self.assertEqual(gzip.decompress(clean), b"http://localhost/?token=[REDACTED]\n")
        self.assertEqual(checksums[sha(source)], sha(clean))
        self.assertEqual(redactor.sanitize_blob(clean), (clean, 0, {}))

    def test_zip_preserves_entry_names_comments_and_other_members(self):
        source = io.BytesIO()
        with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.comment = b"evidence"
            archive.writestr("server.log", b"http://localhost/?token=secret")
            archive.writestr("client.log", b"unchanged")
        clean, count, _ = redactor.sanitize_blob(source.getvalue())
        self.assertEqual(count, 1)
        with zipfile.ZipFile(io.BytesIO(clean)) as archive:
            self.assertEqual(archive.comment, b"evidence")
            self.assertEqual(archive.namelist(), ["server.log", "client.log"])
            self.assertEqual(archive.read("server.log"), b"http://localhost/?token=[REDACTED]")
            self.assertEqual(archive.read("client.log"), b"unchanged")
        self.assertEqual(redactor.sanitize_blob(clean), (clean, 0, {}))

    def test_tar_gzip_rewrites_member_sizes_and_retains_metadata(self):
        source = io.BytesIO()
        with tarfile.open(fileobj=source, mode="w:") as archive:
            content = b"http://localhost/?token=a-very-long-credential-to-remove"
            info = tarfile.TarInfo("nested/server.log")
            info.size = len(content)
            info.mode = 0o640
            info.mtime = 123
            archive.addfile(info, io.BytesIO(content))
        clean, count, _ = redactor.sanitize_blob(gzip.compress(source.getvalue(), mtime=12))
        self.assertEqual(count, 1)
        with tarfile.open(fileobj=io.BytesIO(clean), mode="r:gz") as archive:
            info = archive.getmember("nested/server.log")
            with archive.extractfile(info) as stream:
                content = stream.read()
            self.assertEqual(content, b"http://localhost/?token=[REDACTED]")
            self.assertEqual(info.size, len(content))
            self.assertEqual((info.mode, info.mtime), (0o640, 123))
        self.assertEqual(redactor.sanitize_blob(clean), (clean, 0, {}))

    def test_checksum_references_propagate_from_archive_to_report_and_documents(self):
        raw = b"Paper started http://localhost/?token=secret\n"
        archive = gzip.compress(raw, mtime=0)
        report = json.dumps({"compressed": sha(archive), "raw": sha(raw)}).encode()
        docs = f"Report: {sha(report)}".encode()
        index = f"Docs: {sha(docs)}".encode()
        blobs = {
            "log": ("tools/paper-integration/reports/fixture.log.gz", archive),
            "report": ("tools/paper-integration/reports/fixture.json", report),
            "docs": ("docs/EVIDENCE.md", docs),
            "index": ("README.md", index),
        }
        updates, occurrences, credentials = redactor.prepare(blobs)
        self.assertEqual(occurrences, 1)
        self.assertEqual(credentials, {"log": 1})
        changed = json.loads(updates["report"])
        self.assertEqual(changed["compressed"], sha(updates["log"]))
        self.assertEqual(changed["raw"], sha(gzip.decompress(updates["log"])))
        self.assertEqual(updates["docs"], f"Report: {sha(updates['report'])}".encode())
        self.assertEqual(updates["index"], f"Docs: {sha(updates['docs'])}".encode())

    def test_history_catches_removed_secret_but_leaves_clean_tip_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "tools/paper-integration/reports/fixture.log.gz"
            path.parent.mkdir(parents=True)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            for content in (b"http://localhost/?token=secret", b"http://localhost/?token=[REDACTED]"):
                path.write_bytes(gzip.compress(content, mtime=0))
                subprocess.run(["git", "add", "."], cwd=root, check=True)
                subprocess.run(["git", "-c", "user.name=Fixture", "-c",
                                "user.email=fixture@example.invalid", "commit", "-qm", "fixture"],
                               cwd=root, check=True)
            historical = redactor.read_blobs(root)
            current = redactor.read_blobs(root, current=True)
            updates, count, _ = redactor.prepare(historical)
            self.assertEqual(count, 1)
            self.assertEqual(len(updates), 1)
            self.assertTrue(set(updates).isdisjoint(current))
            self.assertEqual(redactor.prepare(current), ({}, 0, {}))

    def test_broken_archive_fails_closed(self):
        with self.assertRaises((EOFError, OSError)):
            redactor.sanitize_blob(b"\x1f\x8bnot-a-valid-archive")


if __name__ == "__main__":
    unittest.main()
