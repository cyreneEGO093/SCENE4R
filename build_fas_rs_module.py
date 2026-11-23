#!/usr/bin/env python3
"""Reproducible verifier and packager for SCENE4R's fas-rs-r.zip (GNU GPL v3.0)."""

import hashlib
import pathlib
import shutil
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
MODULE_DIR = ROOT / "fas-rs-module"
DIST_DIR = ROOT / "dist"
OUT_ZIP = DIST_DIR / "fas-rs-r.zip"
OUT_SHA = DIST_DIR / "fas-rs-r.sha256"
APP_ASSET_ZIP = ROOT / "scene4-modern" / "app" / "src" / "main" / "assets" / "fas-rs" / "fas-rs-r.zip"

REQUIRED_FILES = [
    "META-INF/com/google/android/update-binary",
    "META-INF/com/google/android/updater-script",
    "LICENSE",
    "module.prop",
    "games.toml",
    "customize.sh",
    "service.sh",
    "uninstall.sh",
    "bin/fas-rs",
]

TEXT_SUFFIXES = {".sh", ".prop", ".toml", ".md"}


def verify_module_files() -> None:
    for rel in REQUIRED_FILES:
        p = MODULE_DIR / rel
        if not p.is_file() or p.stat().st_size == 0:
            raise FileNotFoundError(f"Missing or empty module file: {p}")

    elf = MODULE_DIR / "bin" / "fas-rs"
    magic = elf.read_bytes()[:4]
    if magic != b"\x7fELF":
        raise ValueError(f"bin/fas-rs is not an ELF binary: {elf}")

    # Verify strict security boundaries in scripts and config
    service_sh = (MODULE_DIR / "service.sh").read_text(encoding="utf-8")
    customize_sh = (MODULE_DIR / "customize.sh").read_text(encoding="utf-8")
    for s_name, s_text in (("service.sh", service_sh), ("customize.sh", customize_sh)):
        if "/sdcard/" in s_text or "/dev/fas_rs" in s_text:
            raise ValueError(f"Unsafe legacy path found in {s_name}")

    # Normalize LF line endings on text assets
    for path in sorted(MODULE_DIR.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix in TEXT_SUFFIXES or path.name in {"update-binary", "updater-script", "LICENSE"}:
            raw = path.read_bytes()
            norm = raw.replace(b"\r\n", b"\n")
            if raw != norm:
                path.write_bytes(norm)


def build_zip(sync_asset: bool = True) -> pathlib.Path:
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    APP_ASSET_ZIP.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(OUT_ZIP, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in sorted(MODULE_DIR.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(MODULE_DIR).as_posix()
            data = path.read_bytes()
            zi = zipfile.ZipInfo(filename=rel, date_time=(2026, 11, 23, 0, 0, 0))
            zi.create_system = 3
            is_exec = rel.startswith("bin/") or rel.endswith(".sh") or rel.endswith("update-binary")
            mode = 0o755 if is_exec else 0o644
            zi.external_attr = (0o100000 | mode) << 16
            zf.writestr(zi, data)

    zip_bytes = OUT_ZIP.read_bytes()
    zip_sha = hashlib.sha256(zip_bytes).hexdigest()
    bin_sha = hashlib.sha256((MODULE_DIR / "bin" / "fas-rs").read_bytes()).hexdigest()

    OUT_SHA.write_text(
        f"{zip_sha}  fas-rs-r.zip\n{bin_sha}  bin/fas-rs\n",
        encoding="utf-8",
        newline="\n",
    )
    if sync_asset:
        shutil.copyfile(OUT_ZIP, APP_ASSET_ZIP)

    print(f"bin/fas-rs: {(MODULE_DIR / 'bin' / 'fas-rs').stat().st_size} bytes, SHA-256: {bin_sha}")
    with zipfile.ZipFile(OUT_ZIP, "r") as zf:
        print(f"Built {OUT_ZIP}: {len(zip_bytes)} bytes, {len(zf.namelist())} entries, SHA-256: {zip_sha}")
        for info in zf.infolist():
            print(f"  - {info.filename} ({info.file_size} bytes)")
    return OUT_ZIP


if __name__ == "__main__":
    verify_module_files()
    build_zip(sync_asset=True)
