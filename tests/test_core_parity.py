"""Cross-repository parity test ensuring embedded C.O.R.E. matches the authoritative C.O.R.E. source."""

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


def test_core_source_parity():
    """
    Verifies that K.A.R.E.N.'s embedded src/core module is strictly synchronized
    with the authoritative standalone repository at Agri99/C.O.R.E.
    In CI, both repositories are checked out side-by-side to enforce strict parity.
    """
    karen_core_dir = Path(__file__).parent.parent / "src" / "core"

    candidate_standalone_dirs = [
        Path(__file__).parent.parent.parent / "C.O.R.E." / "src" / "core",
        Path(__file__).parent.parent.parent / "C.O.R.E" / "src" / "core",
        Path(__file__).parent.parent.parent / "CORE" / "src" / "core",
        Path(__file__).parent.parent.parent / "Alexandria" / "src" / "core",
    ]

    standalone_core_dir = None
    for cand in candidate_standalone_dirs:
        if cand.exists() and (cand / "__init__.py").exists():
            standalone_core_dir = cand
            break

    # In CI, enforce that standalone C.O.R.E. was checked out alongside K.A.R.E.N.
    if os.getenv("CI"):
        assert standalone_core_dir is not None, (
            "Authoritative C.O.R.E. source not found in candidate paths. "
            "Gateway CI must checkout Agri99/C.O.R.E. to enforce cross-repository parity."
        )

    # In local isolated test environments where standalone repo is absent, check module completeness
    if standalone_core_dir is None:
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
            assert (karen_core_dir / mod).exists(), f"Missing embedded C.O.R.E. module: {mod}"
        return

    # When both repositories are present locally, enforce strict byte-for-byte parity
    source_files = sorted([f.name for f in standalone_core_dir.glob("*.py")])
    embedded_files = sorted([f.name for f in karen_core_dir.glob("*.py")])

    assert source_files == embedded_files, (
        f"C.O.R.E. module drift: files in standalone {source_files} != files in embedded {embedded_files}"
    )

    mismatches = []
    for fname in source_files:
        src_path = standalone_core_dir / fname
        emb_path = karen_core_dir / fname
        if _sha256(src_path) != _sha256(emb_path):
            mismatches.append(fname)

    assert not mismatches, (
        f"C.O.R.E. module content drift detected in: {mismatches}. "
        f"Please synchronize Agri99/C.O.R.E. to K.A.R.E.N./src/core using scripts/sync_core.py."
    )
