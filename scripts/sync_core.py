#!/usr/bin/env python3
"""
Explicit synchronization utility copying the authoritative C.O.R.E. source
from Agri99/C.O.R.E. into K.A.R.E.N.'s embedded src/core module.
"""

import shutil
from pathlib import Path

def sync():
    root = Path(__file__).resolve().parent.parent
    target_dir = root / "src" / "core"

    candidates = [
        root.parent / "C.O.R.E." / "src" / "core",
        root.parent / "C.O.R.E" / "src" / "core",
        root.parent / "CORE" / "src" / "core",
        root.parent / "Alexandria" / "src" / "core",
        Path("C:/Dev/C.O.R.E./src/core"),
        Path("C:/Dev/C.O.R.E/src/core"),
        Path("C:/Dev/Alexandria/src/core"),
    ]

    source_dir = None
    for c in candidates:
        if c.exists() and (c / "__init__.py").exists():
            source_dir = c
            break

    if not source_dir:
        raise FileNotFoundError("Could not find authoritative C.O.R.E. repository source directory.")

    print(f"Synchronizing from authoritative source: {source_dir}")
    print(f"Target embedded directory: {target_dir}")

    target_dir.mkdir(parents=True, exist_ok=True)
    source_file_names = {src_file.name for src_file in source_dir.glob("*.py")}
    synced_files = []
    for src_file in source_dir.glob("*.py"):
        dest_file = target_dir / src_file.name
        shutil.copy2(src_file, dest_file)
        synced_files.append(src_file.name)

    # Safely remove obsolete embedded source files not present in canonical source
    stale_files = []
    for existing_file in target_dir.glob("*.py"):
        if existing_file.name not in source_file_names:
            existing_file.unlink()
            stale_files.append(existing_file.name)

    print(f"Successfully synchronized {len(synced_files)} module files: {', '.join(sorted(synced_files))}")
    if stale_files:
        print(f"Removed {len(stale_files)} obsolete embedded files: {', '.join(sorted(stale_files))}")

if __name__ == "__main__":
    sync()
