"""Cross-repository parity test ensuring embedded Alexandria matches the authoritative Alexandria source."""

import hashlib
import os
from pathlib import Path
import pytest


def _sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def test_alexandria_source_parity():
    """
    Verifies that Raphael's embedded src/alexandria module is strictly synchronized
    with the authoritative standalone repository at Agri99/Alexandria.
    In CI, both repositories are checked out side-by-side to enforce strict parity.
    """
    raphael_alexandria_dir = Path(__file__).parent.parent / "src" / "alexandria"
    standalone_alexandria_dir = Path(__file__).parent.parent.parent / "Alexandria" / "src" / "alexandria"

    # In CI, enforce that standalone Alexandria was checked out alongside Raphael
    if os.getenv("CI"):
        assert standalone_alexandria_dir.exists(), (
            f"Authoritative Alexandria source not found at {standalone_alexandria_dir}. "
            "Gateway CI must checkout Agri99/Alexandria to enforce cross-repository parity."
        )

    # In local isolated test environments where standalone repo is absent, check module completeness
    if not standalone_alexandria_dir.exists():
        expected_modules = [
            "__init__.py",
            "backup.py",
            "lifecycle.py",
            "migrations.py",
            "models.py",
            "provider.py",
            "sqlite_provider.py",
            "validation.py",
        ]
        for mod in expected_modules:
            assert (raphael_alexandria_dir / mod).exists(), f"Missing embedded Alexandria module: {mod}"
        return

    # When both repositories are present locally, enforce strict byte-for-byte parity
    source_files = sorted([f.name for f in standalone_alexandria_dir.glob("*.py")])
    embedded_files = sorted([f.name for f in raphael_alexandria_dir.glob("*.py")])

    assert source_files == embedded_files, (
        f"Alexandria module drift: files in standalone {source_files} != files in embedded {embedded_files}"
    )

    mismatches = []
    for fname in source_files:
        src_path = standalone_alexandria_dir / fname
        emb_path = raphael_alexandria_dir / fname
        if _sha256(src_path) != _sha256(emb_path):
            mismatches.append(fname)

    assert not mismatches, (
        f"Alexandria module content drift detected in: {mismatches}. "
        f"Please synchronize Agri99/Alexandria to Raphael/src/alexandria using scripts/sync_alexandria.py."
    )
