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
    config_path = Path(__file__).parent / "config.toml"
    if not config_path.exists():
        config_path = Path("config.toml")

    if config_path.exists():
        with open(config_path, "r", encoding="utf-8") as f:
            doc = tomlkit.load(f)
        if "auth" not in doc:
            doc["auth"] = tomlkit.table()
        doc["auth"]["bearer_token"] = new_token
        with open(config_path, "w", encoding="utf-8") as f:
            tomlkit.dump(doc, f)
        print("Generated new bearer token and updated config.toml.")
        print("New Bearer Token (record securely in Raphael settings, do not commit):")
        print(new_token)
    else:
        print("config.toml not found. Generated token:")
        print(new_token)


def status_cmd(args):
    print("Gateway Configuration:")
    print(f"Host: {SETTINGS.server.host}")
    print(f"Port: {SETTINGS.server.port}")
    print(f"Storage Path: {SETTINGS.storage.database_path}")
    print("Configured Roles:")
    for role, cfg in SETTINGS.roles.items():
        print(f"  - {role}: model={cfg.model}, num_ctx={cfg.num_ctx}, num_predict={cfg.num_predict}")


def main():
    parser = argparse.ArgumentParser(description="Raphael Restricted Gateway Operations CLI")
    subparsers = parser.add_subparsers(dest="command")

    subparsers.add_parser("rotate-token", help="Generate and set a new random 32-byte bearer token")
    subparsers.add_parser("status", help="Print gateway operational configuration and role mapping")

    args = parser.parse_args()
    if args.command == "rotate-token":
        rotate_token_cmd(args)
    elif args.command == "status":
        status_cmd(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
