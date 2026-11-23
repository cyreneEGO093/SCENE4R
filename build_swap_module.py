#!/usr/bin/env python3
"""Reproducible compiler and packager for SCENE4R's scene-swap-controller.zip."""

import hashlib
import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
MODULE_DIR = ROOT / "swap-controller-module"
DIST_DIR = ROOT / "dist"
OUT_ZIP = DIST_DIR / "scene-swap-controller.zip"
OUT_SHA = DIST_DIR / "scene-swap-controller.sha256"
APP_ASSET_ZIP = (
    ROOT / "scene4-modern" / "app" / "src" / "main" / "assets" / "swap" / "scene-swap-controller.zip"
)

TEXT_SUFFIXES = {".sh", ".prop", ".md"}
EXEC_FILES = {
    "META-INF/com/google/android/update-binary",
    "customize.sh",
    "service.sh",
    "uninstall.sh",
    "system/bin/scene_swap_module.sh",
    "system/bin/scene_swap_extra.sh",
}


def resolve_java_home() -> pathlib.Path:
    env_val = os.environ.get("JAVA_HOME")
    if env_val:
        p = pathlib.Path(env_val)
        if p.is_dir():
            return p
        raise FileNotFoundError(f"JAVA_HOME does not point to an existing directory: {p}")
    javac_on_path = shutil.which("javac")
    if javac_on_path:
        return pathlib.Path(javac_on_path).resolve().parent.parent
    raise RuntimeError("JAVA_HOME is not set and javac was not found in PATH.")


def resolve_android_home() -> pathlib.Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        env_val = os.environ.get(var)
        if env_val:
            p = pathlib.Path(env_val)
            if p.is_dir():
                return p
            raise FileNotFoundError(f"{var} does not point to an existing directory: {p}")
    raise RuntimeError("Neither ANDROID_HOME nor ANDROID_SDK_ROOT is set.")


def _version_key(name: str) -> tuple[int, ...]:
    nums = []
    for part in name.replace("-", ".").split("."):
        if part.isdigit():
            nums.append(int(part))
    return tuple(nums)


def find_d8(android_home: pathlib.Path) -> pathlib.Path:
    preferred = android_home / "build-tools" / "35.0.0"
    search_dirs = [preferred] if preferred.is_dir() else []
    build_tools = android_home / "build-tools"
    if build_tools.is_dir():
        others = sorted(
            [d for d in build_tools.iterdir() if d.is_dir() and d != preferred],
            key=lambda d: _version_key(d.name),
            reverse=True,
        )
        search_dirs.extend(others)
    for d in search_dirs:
        for name in ("d8.bat", "d8"):
            candidate = d / name
            if candidate.is_file():
                return candidate
    raise FileNotFoundError(f"Could not find d8 under {build_tools}")


def find_android_jar(android_home: pathlib.Path) -> pathlib.Path:
    preferred = android_home / "platforms" / "android-36" / "android.jar"
    if preferred.is_file():
        return preferred
    platforms = android_home / "platforms"
    if platforms.is_dir():
        candidates = sorted(
            [d / "android.jar" for d in platforms.iterdir() if d.is_dir() and (d / "android.jar").is_file()],
            key=lambda p: _version_key(p.parent.name),
            reverse=True,
        )
        if candidates:
            return candidates[0]
    raise FileNotFoundError(f"Missing android.jar under {platforms}")


def compile_dex() -> pathlib.Path:
    src_file = MODULE_DIR / "src" / "Main.java"
    out_dex = MODULE_DIR / "system" / "bin" / "scene_swap_service.dex"
    out_dex.parent.mkdir(parents=True, exist_ok=True)

    java_home = resolve_java_home()
    android_home = resolve_android_home()
    javac = java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    d8 = find_d8(android_home)
    android_jar = find_android_jar(android_home)
    if not javac.is_file():
        raise FileNotFoundError(f"Missing javac: {javac}")

    sub_env = os.environ.copy()
    sub_env["JAVA_HOME"] = str(java_home)
    sub_env["ANDROID_HOME"] = str(android_home)
    sub_env["PATH"] = f"{java_home / 'bin'}{os.pathsep}{sub_env.get('PATH', '')}"

    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = pathlib.Path(tmp)
        classes_dir = tmp_dir / "classes"
        dex_out_dir = tmp_dir / "dex"
        classes_dir.mkdir()
        dex_out_dir.mkdir()

        subprocess.run(
            [
                str(javac),
                "--release",
                "8",
                "-encoding",
                "UTF-8",
                "-d",
                str(classes_dir),
                str(src_file),
            ],
            env=sub_env,
            check=True,
        )

        class_files = sorted(str(p) for p in classes_dir.rglob("*.class"))
        if not class_files:
            raise RuntimeError("javac produced no .class files")

        subprocess.run(
            [
                str(d8),
                "--min-api",
                "26",
                "--lib",
                str(android_jar),
                "--output",
                str(dex_out_dir),
                *class_files,
            ],
            env=sub_env,
            check=True,
        )

        built_dex = dex_out_dir / "classes.dex"
        if not built_dex.is_file():
            raise RuntimeError("d8 produced no classes.dex")
        shutil.copy2(built_dex, out_dex)

    return out_dex


def build_zip() -> pathlib.Path:
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    APP_ASSET_ZIP.parent.mkdir(parents=True, exist_ok=True)

    files = []
    for path in sorted(MODULE_DIR.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(MODULE_DIR).as_posix()
        if rel.startswith("src/"):
            continue
        files.append((path, rel))

    with zipfile.ZipFile(OUT_ZIP, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for src, rel in files:
            data = src.read_bytes()
            if src.suffix in TEXT_SUFFIXES or src.name == "update-binary" or src.name == "LICENSE":
                data = data.replace(b"\r\n", b"\n")
            zi = zipfile.ZipInfo(filename=rel, date_time=(2026, 11, 22, 0, 0, 0))
            zi.create_system = 3
            mode = 0o755 if (rel in EXEC_FILES or rel.endswith(".sh")) else 0o644
            zi.external_attr = (0o100000 | mode) << 16
            zf.writestr(zi, data, compress_type=zipfile.ZIP_DEFLATED)

    shutil.copy2(OUT_ZIP, APP_ASSET_ZIP)
    zip_sha = hashlib.sha256(OUT_ZIP.read_bytes()).hexdigest()
    OUT_SHA.write_text(f"{zip_sha}  scene-swap-controller.zip\n", encoding="utf-8")
    return OUT_ZIP


def main() -> None:
    dex_path = compile_dex()
    zip_path = build_zip()
    dex_sha = hashlib.sha256(dex_path.read_bytes()).hexdigest()
    zip_bytes = zip_path.read_bytes()
    zip_sha = hashlib.sha256(zip_bytes).hexdigest()

    print(f"Compiled {dex_path.name}: {dex_path.stat().st_size} bytes, SHA-256: {dex_sha}")
    with zipfile.ZipFile(zip_path, "r") as zf:
        print(f"Built {zip_path}: {len(zip_bytes)} bytes, {len(zf.namelist())} entries, SHA-256: {zip_sha}")
        for info in zf.infolist():
            print(f"  - {info.filename} ({info.file_size} bytes)")


if __name__ == "__main__":
    main()
