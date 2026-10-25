"""Build and verify signed release APK for Raphael."""

import os
from pathlib import Path
import subprocess
import sys


REPO_ROOT = Path(__file__).resolve().parent.parent
KEYSTORE_FILE = REPO_ROOT / "keystore" / "raphael_release.jks"
CREDENTIALS_FILE = REPO_ROOT / "keystore" / "release_credentials.local"


def find_java_home() -> str:
    candidate = Path("C:/Program Files/Android/Android Studio/jbr")
    if candidate.is_dir():
        return str(candidate)
    return os.environ.get("JAVA_HOME", "")


def build_signed_release():
    if not KEYSTORE_FILE.exists() or not CREDENTIALS_FILE.exists():
        print("Keystore or credentials not found. Running manage_release_signing.py first...")
        subprocess.run([sys.executable, str(REPO_ROOT / "scripts" / "manage_release_signing.py")], check=True)

    password = CREDENTIALS_FILE.read_text(encoding="utf-8").strip()
    java_home = find_java_home()

    env = os.environ.copy()
    env["JAVA_HOME"] = java_home
    env["RAPHAEL_KEYSTORE_PATH"] = str(KEYSTORE_FILE)
    env["RAPHAEL_KEYSTORE_PASSWORD"] = password
    env["RAPHAEL_KEY_ALIAS"] = "raphael_release"
    env["RAPHAEL_KEY_PASSWORD"] = password

    gradlew = str(REPO_ROOT / "gradlew.bat") if os.name == "nt" else str(REPO_ROOT / "gradlew")

    print(f"Building release APK with: {gradlew} assembleRelease")
    cmd = [gradlew, "assembleRelease", "--no-daemon", "--stacktrace"]
    res = subprocess.run(cmd, cwd=str(REPO_ROOT), env=env)
    if res.returncode != 0:
        print(f"assembleRelease exited with code {res.returncode}")
        return False

    apk_dir = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "release"
    apks = list(apk_dir.glob("*.apk"))
    if not apks:
        print(f"No APK found in {apk_dir}")
        return False

    target_apk = apks[0]
    print(f"Found release APK: {target_apk} ({target_apk.stat().st_size} bytes)")

    # Verify signature
    keytool = Path(java_home) / "bin" / "keytool.exe" if os.name == "nt" else Path(java_home) / "bin" / "keytool"
    if keytool.exists():
        print("Verifying APK certificate...")
        verify_res = subprocess.run([str(keytool), "-printcert", "-jarfile", str(target_apk)], capture_output=True, text=True)
        print(verify_res.stdout)
        if "SHA256:" in verify_res.stdout:
            print("APK Signature verification successful!")

    return True


if __name__ == "__main__":
    success = build_signed_release()
    sys.exit(0 if success else 1)
