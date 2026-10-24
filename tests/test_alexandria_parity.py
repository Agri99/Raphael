"""Cross-repository parity test ensuring embedded Alexandria matches the authoritative Alexandria source."""

import hashlib
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
    with the authoritative standalone repository at C:/Dev/Alexandria.
    """
    raphael_alexandria_dir = Path(__file__).parent.parent / "src" / "alexandria"
    standalone_alexandria_dir = Path(__file__).parent.parent.parent / "Alexandria" / "src" / "alexandria"

    # In standalone CI where only Raphael is cloned, verify embedded package integrity
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
        f"Please synchronize C:/Dev/Alexandria/src/alexandria to C:/Dev/Raphael/src/alexandria."
    )
