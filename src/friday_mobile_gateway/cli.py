"""Command-line interface for gateway operations, token rotation, and diagnostics."""

import argparse
import sys
from pathlib import Path
try:
    import tomllib
except ImportError:
    import tomlkit as tomllib

import tomlkit
from .auth import generate_secure_token
from .config import SETTINGS, load_settings


def rotate_token_cmd(args):
    new_token = generate_secure_token()
    # Write rotated secrets to config.local.toml (excluded by .gitignore)
    local_config_path = Path(__file__).parent / "config.local.toml"
    base_config_path = Path(__file__).parent / "config.toml"

    doc = tomlkit.document()
    if base_config_path.exists():
        with open(base_config_path, "r", encoding="utf-8") as f:
            doc = tomlkit.load(f)

    if "auth" not in doc:
        doc["auth"] = tomlkit.table()
    doc["auth"]["bearer_token"] = new_token

    with open(local_config_path, "w", encoding="utf-8") as f:
        tomlkit.dump(doc, f)

    print("Generated new bearer token and saved to uncommitted config.local.toml.")
    print("New Bearer Token (record securely in K.A.R.E.N. Android settings, do not commit):")
    print(new_token)


def status_cmd(args):
    print("Gateway Configuration:")
    print(f"Host: {SETTINGS.server.host}")
    print(f"Port: {SETTINGS.server.port}")
    print(f"Storage Path: {SETTINGS.storage.database_path}")
    print("Configured Roles:")
    for role, cfg in SETTINGS.roles.items():
        print(f"  - {role}: model={cfg.model}, num_ctx={cfg.num_ctx}, num_predict={cfg.num_predict}")


def restore_db_cmd(args):
    from core.backup import restore_sqlite_backup
    backup_file = Path(args.backup_path).resolve()
    if not backup_file.exists():
        print(f"Error: Backup file not found at {backup_file}", file=sys.stderr)
        sys.exit(1)

    db_path = Path(SETTINGS.storage.database_path)
    if not db_path.is_absolute():
        db_path = Path(__file__).parent.parent.parent / db_path

    print(f"Initiating offline restore to target: {db_path}")
    print(f"Source backup: {backup_file}")
    try:
        restore_sqlite_backup(backup_file, db_path)
        print("Offline SQLite database restored successfully.")
    except RuntimeError as exc:
        print(f"Restoration halted by offline policy: {exc}", file=sys.stderr)
        print("Please ensure the gateway process (uvicorn) is stopped before restoring.", file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="K.A.R.E.N. Restricted Gateway Operations CLI")
    subparsers = parser.add_subparsers(dest="command")

    subparsers.add_parser("rotate-token", help="Generate and set a new random 32-byte bearer token in config.local.toml")
    subparsers.add_parser("status", help="Print gateway operational configuration and role mapping")
    
    restore_parser = subparsers.add_parser("restore-db", help="Perform verified offline restoration of the SQLite memory database")
    restore_parser.add_argument("backup_path", help="Path to verified .sqlite3 backup file")

    args = parser.parse_args()
    if args.command == "rotate-token":
        rotate_token_cmd(args)
    elif args.command == "status":
        status_cmd(args)
    elif args.command == "restore-db":
        restore_db_cmd(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
