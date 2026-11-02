#!/usr/bin/env python3
"""Rebuild Ink NFC Lab (dev.inknfclab) with only the installed Android SDK + JDK.

No Gradle, no network, no third-party libraries.

Pipeline:
    javac  -> classes/  -> classes.jar
    d8     -> classes.dex
    aapt2 compile/link (res + assets + manifest) -> unsigned APK
    inject classes.dex -> zipalign -> apksigner (local debug keystore) -> verify
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
DIST = ROOT / "dist"
KEYSTORE = ROOT / "keystore" / "debug.keystore"
KEY_ALIAS = "inknfclab"
KEY_PASS = "android"

SDK = Path(os.environ.get("ANDROID_HOME", r"D:\Apps\AndroidSDK"))
JDK = Path(os.environ.get("JAVA_HOME", r"D:\Apps\OpenJDK"))

BUILD_TOOLS_PREFERENCE = ["36.1.0", "35.0.0", "34.0.0", "33.0.1"]
PLATFORM_PREFERENCE = ["android-36.1", "android-36", "android-35", "android-34"]

MIN_SDK = "26"
TARGET_SDK = "35"


def find_build_tools() -> Path:
    for name in BUILD_TOOLS_PREFERENCE:
        candidate = SDK / "build-tools" / name
        if (candidate / "aapt2.exe").is_file() or (candidate / "aapt2").is_file():
            return candidate
    tools = sorted((SDK / "build-tools").glob("*"))
    if not tools:
        raise SystemExit(f"No Android build-tools found under {SDK / 'build-tools'}")
    return tools[-1]


def find_platform() -> Path:
    for name in PLATFORM_PREFERENCE:
        candidate = SDK / "platforms" / name / "android.jar"
        if candidate.is_file():
            return candidate
    jars = sorted((SDK / "platforms").glob("*/android.jar"))
    if not jars:
        raise SystemExit(f"No platforms/*/android.jar found under {SDK / 'platforms'}")
    return jars[-1]


BT = find_build_tools()
ANDROID_JAR = find_platform()
AAPT2 = BT / ("aapt2.exe" if os.name == "nt" else "aapt2")
ZIPALIGN = BT / ("zipalign.exe" if os.name == "nt" else "zipalign")
D8_JAR = BT / "lib" / "d8.jar"
APKSIGNER_JAR = BT / "lib" / "apksigner.jar"
JAVA = JDK / "bin" / ("java.exe" if os.name == "nt" else "java")
JAVAC = JDK / "bin" / ("javac.exe" if os.name == "nt" else "javac")
JAR = JDK / "bin" / ("jar.exe" if os.name == "nt" else "jar")
KEYTOOL = JDK / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")


def run(*args: object, cwd: Path | None = None) -> None:
    command = [str(a) for a in args]
    printable = " ".join(command)
    print("+ " + printable)
    result = subprocess.run(command, cwd=cwd or ROOT)
    if result.returncode != 0:
        raise SystemExit(f"command failed ({result.returncode}): {printable}")


def check_tools() -> None:
    missing = [p for p in (AAPT2, ZIPALIGN, D8_JAR, APKSIGNER_JAR, JAVA, JAVAC, JAR, KEYTOOL, ANDROID_JAR) if not p.is_file()]
    if missing:
        raise SystemExit("missing tool(s):\n  " + "\n  ".join(str(p) for p in missing))
    print(f"build-tools: {BT}")
    print(f"android.jar: {ANDROID_JAR}")
    print(f"JDK        : {JDK}")


def clean() -> None:
    for directory in (BUILD / "classes", BUILD / "dex", BUILD / "res"):
        shutil.rmtree(directory, ignore_errors=True)
    for name in ("classes.jar", "res.zip", "unsigned.apk", "aligned.apk"):
        (BUILD / name).unlink(missing_ok=True)
    for directory in (BUILD / "classes", BUILD / "dex", BUILD / "res", DIST):
        directory.mkdir(parents=True, exist_ok=True)


def compile_sources() -> None:
    sources = sorted((ROOT / "src").rglob("*.java"))
    if not sources:
        raise SystemExit("no Java sources under src/")
    print(f"javac: {len(sources)} source files")
    run(
        JAVAC,
        "-encoding",
        "UTF-8",
        "--release",
        "8",
        "-Xlint:-options",
        "-classpath",
        ANDROID_JAR,
        "-d",
        BUILD / "classes",
        *sources,
    )
    run(JAR, "cf", BUILD / "classes.jar", "-C", BUILD / "classes", ".")


def build_dex() -> None:
    run(
        JAVA,
        "-cp",
        D8_JAR,
        "com.android.tools.r8.D8",
        "--min-api",
        MIN_SDK,
        "--lib",
        ANDROID_JAR,
        "--output",
        BUILD / "dex",
        BUILD / "classes.jar",
    )
    if not (BUILD / "dex" / "classes.dex").is_file():
        raise SystemExit("d8 did not produce build/dex/classes.dex")


def link_resources() -> None:
    run(AAPT2, "compile", "--dir", ROOT / "res", "-o", BUILD / "res.zip")
    run(
        AAPT2,
        "link",
        "-o",
        BUILD / "unsigned.apk",
        "-I",
        ANDROID_JAR,
        "--manifest",
        ROOT / "AndroidManifest.xml",
        "-A",
        ROOT / "assets",
        "--min-sdk-version",
        MIN_SDK,
        "--target-sdk-version",
        TARGET_SDK,
        "--no-compile-sdk-metadata",
        BUILD / "res.zip",
    )
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as archive:
        archive.write(BUILD / "dex" / "classes.dex", "classes.dex")


def ensure_keystore() -> None:
    if KEYSTORE.is_file():
        return
    KEYSTORE.parent.mkdir(parents=True, exist_ok=True)
    run(
        KEYTOOL,
        "-genkeypair",
        "-keystore",
        KEYSTORE,
        "-storepass",
        KEY_PASS,
        "-keypass",
        KEY_PASS,
        "-alias",
        KEY_ALIAS,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "10000",
        "-dname",
        "CN=Ink NFC Lab Local Debug, OU=lab, O=inknfclab, C=CN",
        "-noprompt",
    )


def manifest_version_name() -> str:
    manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
    return manifest.attrib["{http://schemas.android.com/apk/res/android}versionName"]


def sign_and_align() -> None:
    run(ZIPALIGN, "-f", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk")
    ensure_keystore()
    apk = DIST / f"InkNfcLab-{manifest_version_name()}.apk"
    apk.unlink(missing_ok=True)
    run(
        JAVA,
        "-jar",
        APKSIGNER_JAR,
        "sign",
        "--ks",
        KEYSTORE,
        "--ks-key-alias",
        KEY_ALIAS,
        "--ks-pass",
        "pass:" + KEY_PASS,
        "--key-pass",
        "pass:" + KEY_PASS,
        "--out",
        apk,
        BUILD / "aligned.apk",
    )
    run(JAVA, "-jar", APKSIGNER_JAR, "verify", "--verbose", apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (DIST / (apk.name + ".sha256")).write_text(digest + "  " + apk.name + "\n", encoding="utf-8")
    print(f"APK    : {apk}")
    print(f"SHA256 : {digest}")


def main() -> None:
    if not (ROOT / "AndroidManifest.xml").is_file():
        raise SystemExit(f"run this script from the project root (missing {ROOT / 'AndroidManifest.xml'})")
    check_tools()
    clean()
    compile_sources()
    build_dex()
    link_resources()
    sign_and_align()
    print("build ok")


if __name__ == "__main__":
    sys.exit(main())
