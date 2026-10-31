"""Release Signing Key Generation, Verification, Backup, and Recovery Tool for K.A.R.E.N.

This utility manages the release signing key lifecycle:
1. Generates PKCS12 release keystore (RSA 4096-bit, SHA256withRSA, 10,000 days validity).
2. Computes and records certificate SHA-256 fingerprints.
3. Creates isolated backup archives.
4. Executes offline key recovery drills to verify restored keystore integrity.
"""

import hashlib
import os
from pathlib import Path
import secrets
import shutil
import string
import subprocess
import sys
import zipfile


REPO_ROOT = Path(__file__).resolve().parent.parent
KEYSTORE_DIR = REPO_ROOT / "keystore"
KEYSTORE_FILE = KEYSTORE_DIR / "karen_release.jks"
# Legacy fallback
if not KEYSTORE_FILE.exists() and (KEYSTORE_DIR / "raphael_release.jks").exists():
    KEYSTORE_FILE = KEYSTORE_DIR / "raphael_release.jks"

BACKUP_DIR = KEYSTORE_DIR / "backup"
CREDENTIALS_FILE = KEYSTORE_DIR / "release_credentials.local"
ALIAS = "karen_release" if "karen" in KEYSTORE_FILE.name else "raphael_release"
DNAME = "CN=K.A.R.E.N. Personal AI, OU=Production, O=Agri, C=ID"
VALIDITY_DAYS = 10000


def find_keytool() -> str:
    """Find keytool executable from JAVA_HOME or Android Studio JBR."""
    candidate_paths = [
        os.environ.get("JAVA_HOME", "") + "/bin/keytool.exe",
        os.environ.get("JAVA_HOME", "") + "/bin/keytool",
        "C:/Program Files/Android/Android Studio/jbr/bin/keytool.exe",
        "keytool",
    ]
    for p in candidate_paths:
        if p and (Path(p).is_file() or shutil.which(p)):
            return str(p)
    raise FileNotFoundError("keytool not found in PATH or Android Studio JBR.")


def generate_secure_password(length: int = 32) -> str:
    """Generate a high-entropy passphrase."""
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def load_or_create_credentials() -> str:
    """Load existing password or generate and persist securely."""
    KEYSTORE_DIR.mkdir(parents=True, exist_ok=True)
    if CREDENTIALS_FILE.exists():
        return CREDENTIALS_FILE.read_text(encoding="utf-8").strip()
    
    password = generate_secure_password()
    CREDENTIALS_FILE.write_text(password, encoding="utf-8")
    return password


def generate_keystore(keytool: str, password: str) -> None:
    """Generate PKCS12 keystore if it doesn't already exist."""
    if KEYSTORE_FILE.exists():
        print(f"Keystore already exists at: {KEYSTORE_FILE}")
        return

    print("Generating RSA 4096-bit release keystore...")
    cmd = [
        keytool,
        "-genkeypair",
        "-v",
        "-keystore", str(KEYSTORE_FILE),
        "-alias", ALIAS,
        "-keyalg", "RSA",
        "-keysize", "4096",
        "-sigalg", "SHA256withRSA",
        "-validity", str(VALIDITY_DAYS),
        "-dname", DNAME,
        "-storepass", password,
        "-keypass", password,
        "-storetype", "PKCS12",
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"keytool failed:\n{res.stderr}")
    print(f"Generated release keystore at: {KEYSTORE_FILE}")


def inspect_keystore(keytool: str, keystore_path: Path, password: str) -> dict:
    """Inspect keystore and extract alias and fingerprints."""
    cmd = [
        keytool,
        "-list",
        "-v",
        "-keystore", str(keystore_path),
        "-alias", ALIAS,
        "-storepass", password,
        "-storetype", "PKCS12",
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"keytool list failed:\n{res.stderr}")
    
    output = res.stdout
    sha256 = ""
    for line in output.splitlines():
        line_clean = line.strip()
        if line_clean.startswith("SHA256:"):
            sha256 = line_clean.split("SHA256:", 1)[1].strip()
            break

    keystore_sha256 = hashlib.sha256(keystore_path.read_bytes()).hexdigest()
    return {
        "alias": ALIAS,
        "cert_sha256": sha256,
        "file_sha256": keystore_sha256,
        "file_size": keystore_path.stat().st_size,
    }


def backup_keystore(password: str) -> Path:
    """Create a backup archive of the keystore and metadata."""
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    backup_zip = BACKUP_DIR / "karen_release_key_backup.zip"
    
    with zipfile.ZipFile(backup_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(KEYSTORE_FILE, arcname=KEYSTORE_FILE.name)
        zf.writestr(
            "RECOVERY_METADATA.txt",
            f"Alias: {ALIAS}\nDName: {DNAME}\nValidityDays: {VALIDITY_DAYS}\n"
        )
    print(f"Created key backup archive: {backup_zip}")
    return backup_zip


def run_recovery_drill(keytool: str, backup_zip: Path, password: str) -> bool:
    """Perform an offline key recovery drill into an isolated drill directory."""
    print("\n=== RUNNING KEY RECOVERY DRILL ===")
    drill_dir = KEYSTORE_DIR / "drill_recovery"
    drill_dir.mkdir(parents=True, exist_ok=True)
    recovered_keystore = drill_dir / f"recovered_{KEYSTORE_FILE.name}"

    if recovered_keystore.exists():
        recovered_keystore.unlink()

    # Restore from backup archive
    with zipfile.ZipFile(backup_zip, "r") as zf:
        zf.extract(KEYSTORE_FILE.name, drill_dir)
    
    shutil.move(drill_dir / KEYSTORE_FILE.name, recovered_keystore)
    print(f"Extracted keystore from backup to: {recovered_keystore}")

    # Inspect recovered keystore
    original_info = inspect_keystore(keytool, KEYSTORE_FILE, password)
    recovered_info = inspect_keystore(keytool, recovered_keystore, password)

    print(f"Original cert SHA-256:  {original_info['cert_sha256']}")
    print(f"Recovered cert SHA-256: {recovered_info['cert_sha256']}")
    assert original_info["cert_sha256"] == recovered_info["cert_sha256"], "Certificate SHA-256 mismatch!"
    assert original_info["file_sha256"] == recovered_info["file_sha256"], "Keystore file SHA-256 mismatch!"
    print("Recovery drill passed: Restored keystore matches original certificate and file hashes exactly.")

    # Clean up drill artifacts
    shutil.rmtree(drill_dir, ignore_errors=True)
    return True


def main():
    keytool = find_keytool()
    print(f"Using keytool: {keytool}")
    password = load_or_create_credentials()
    generate_keystore(keytool, password)
    info = inspect_keystore(keytool, KEYSTORE_FILE, password)
    print(f"Keystore Certificate SHA-256: {info['cert_sha256']}")
    print(f"Keystore File SHA-256:        {info['file_sha256']}")
    backup_zip = backup_keystore(password)
    run_recovery_drill(keytool, backup_zip, password)
    print("\nRelease signing key setup, backup, and recovery drill completed successfully.")


if __name__ == "__main__":
    main()
