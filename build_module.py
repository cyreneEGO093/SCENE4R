#!/usr/bin/env python3
"""Reproducible builder and verifier for uperf-r.zip."""

import hashlib
import json
import math
import pathlib
import re
import shutil
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
MODULE_DIR = ROOT / "uperf-r-module"
DIST_DIR = ROOT / "dist"
OUT_ZIP = DIST_DIR / "uperf-r.zip"
OUT_SUMS = DIST_DIR / "SHA256SUMS.txt"
APP_ASSET_ZIP = ROOT / "scene4-modern" / "app" / "src" / "main" / "assets" / "uperf" / "uperf-r.zip"

PINNED_ELF_SHA256 = {
    "bin/uperf": "f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8",
    "bin/busybox/busybox": "f03b1cda5b19da390d96befc1e26661bcf6570fd940ff55537ff113825b38dc9",
}

TEXT_SUFFIXES = {".sh", ".json", ".prop", ".txt", ".md", ".awk", ".xml"}
TOP_LEVEL_KEYS = {"meta", "modules", "presets", "initials"}

# Exact 42 verified sysfs/cpuset/proc knob targets (excludes xperf power_limit & /proc/sys/*).
ALLOWED_KNOB_PATHS = {
    "/dev/cpuset/background/cpus",
    "/dev/cpuset/foreground/cpus",
    "/dev/cpuset/restricted/cpus",
    "/dev/cpuset/system-background/cpus",
    "/dev/cpuset/top-app/cpus",
    "/proc/cpufreq/cpufreq_cci_mode",
    "/sys/class/devfreq/18590100.qcom,cpu4-cpu-l3-lat/max_freq",
    "/sys/class/devfreq/18590100.qcom,cpu4-cpu-l3-lat/min_freq",
    "/sys/class/devfreq/1d84000.ufshc/max_freq",
    "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/max_freq",
    "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/min_freq",
    "/sys/class/devfreq/kgsl-busmon/max_freq",
    "/sys/class/devfreq/kgsl-busmon/min_freq",
    "/sys/class/devfreq/mtk-dvfsrc-devfreq/userspace/set_freq",
    "/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw/max_freq",
    "/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw/min_freq",
    "/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw/max_freq",
    "/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw/min_freq",
    "/sys/class/devfreq/soc:qcom,cpu0-llcc-ddr-lat/mem_latency/ratio_ceil",
    "/sys/class/devfreq/soc:qcom,cpu0-llcc-ddr-lat/min_freq",
    "/sys/class/devfreq/soc:qcom,cpu4-llcc-ddr-lat/max_freq",
    "/sys/class/devfreq/soc:qcom,cpu4-llcc-ddr-lat/mem_latency/ratio_ceil",
    "/sys/class/devfreq/soc:qcom,cpu4-llcc-ddr-lat/min_freq",
    "/sys/devices/platform/boot_dramboost/dramboost/dramboost",
    "/sys/devices/platform/exynos-ufcc/ufc/cpufreq_max_limit",
    "/sys/devices/platform/exynos-ufcc/ufc/cpufreq_min_limit",
    "/sys/devices/system/cpu/bus_dcvs/DDR/hw_max_freq",
    "/sys/devices/system/cpu/bus_dcvs/DDR/hw_min_freq",
    "/sys/devices/system/cpu/bus_dcvs/L3/hw_max_freq",
    "/sys/devices/system/cpu/bus_dcvs/L3/hw_min_freq",
    "/sys/devices/system/cpu/bus_dcvs/LLCC/hw_max_freq",
    "/sys/devices/system/cpu/bus_dcvs/LLCC/hw_min_freq",
    "/sys/devices/system/cpu/cpu5/core_ctl/enable",
    "/sys/devices/system/cpu/cpu5/core_ctl/max_cpus",
    "/sys/devices/system/cpu/cpu5/core_ctl/min_cpus",
    "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
    "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq",
    "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq",
    "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq",
    "/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq",
    "/sys/kernel/gpu/gpu_max_clock",
    "/sys/kernel/gpu/gpu_min_clock",
}
SAFE_SYSFS_STR = re.compile(r"^[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$")


def is_allowed_knob(path: str) -> bool:
    return isinstance(path, str) and ".." not in path and path in ALLOWED_KNOB_PATHS


def validate_sysfs_val(val) -> bool:
    if isinstance(val, bool):
        return False
    if isinstance(val, (int, float)):
        return math.isfinite(val) and 0 <= val <= 10_000_000_000
    if isinstance(val, str):
        if len(val) > 64 or not SAFE_SYSFS_STR.match(val):
            return False
        parts = val.split(",")
        if len(parts) > 16:
            return False
        for part in parts:
            segs = part.split("-")
            nums = []
            for seg in segs:
                if len(seg) > 11:
                    return False
                n = int(seg)
                if n > 10_000_000_000:
                    return False
                nums.append(n)
            if len(nums) == 2 and nums[0] > nums[1]:
                return False
        return True
    return False


def check_depth_and_numbers(obj, depth: int = 0) -> None:
    if depth > 16:
        raise ValueError("JSON nesting depth exceeds 16")
    if isinstance(obj, dict):
        for k, v in obj.items():
            if "/" in k:
                raise ValueError(f"Slash in JSON key not permitted: {k}")
            check_depth_and_numbers(v, depth + 1)
    elif isinstance(obj, list):
        for item in obj:
            check_depth_and_numbers(item, depth + 1)
    elif isinstance(obj, float):
        if not math.isfinite(obj) or abs(obj) > 1e10:
            raise ValueError(f"Out-of-range float value: {obj}")
    elif isinstance(obj, int) and not isinstance(obj, bool):
        if abs(obj) > 10_000_000_000:
            raise ValueError(f"Out-of-range int value: {obj}")


def verify_binaries() -> None:
    for rel, expected in PINNED_ELF_SHA256.items():
        actual = hashlib.sha256((MODULE_DIR / rel).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f"Pinned binary mismatch for {rel}: {actual} != {expected}")


def verify_configs() -> None:
    cfg_dir = MODULE_DIR / "config"
    for p in sorted(cfg_dir.glob("*.json")):
        raw = p.read_text(encoding="utf-8")
        if "\\u" in raw:
            raise ValueError(f"Disallowed unicode escape in {p.name}")
        dups = []

        def hook(pairs):
            seen = {}
            for k, v in pairs:
                if k in seen:
                    dups.append(k)
                seen[k] = v
            return seen

        data = json.loads(raw, object_pairs_hook=hook)
        if dups:
            raise ValueError(f"Duplicate keys in {p.name}: {dups}")
        if not isinstance(data, dict) or not set(data.keys()).issubset(TOP_LEVEL_KEYS):
            raise ValueError(f"Unexpected top-level keys in {p.name}: {set(data.keys()) - TOP_LEVEL_KEYS}")
        if "meta" in data and not isinstance(data["meta"], dict):
            raise ValueError(f"Invalid meta object in {p.name}")
        check_depth_and_numbers(data)

        switcher = data.get("modules", {}).get("switcher", {})
        if switcher.get("switchInode") != "/data/adb/uperf/cur_powermode.txt":
            raise ValueError(f"Unsafe switchInode in {p.name}")
        if switcher.get("perapp") != "/data/adb/uperf/perapp_powermode.txt":
            raise ValueError(f"Unsafe perapp in {p.name}")

        knobs = data.get("modules", {}).get("sysfs", {}).get("knob", {})
        if not isinstance(knobs, dict):
            raise ValueError(f"Invalid sysfs.knob in {p.name}")
        for kname, kpath in knobs.items():
            if not is_allowed_knob(kpath):
                raise ValueError(f"Unsafe knob {kname}={kpath} in {p.name}")

        init_sysfs = data.get("initials", {}).get("sysfs", {})
        if not isinstance(init_sysfs, dict):
            raise ValueError(f"Invalid initials.sysfs in {p.name}")
        for k, v in init_sysfs.items():
            if not validate_sysfs_val(v):
                raise ValueError(f"Unsafe initials.sysfs.{k}={v} in {p.name}")

        presets = data.get("presets", {})
        if not isinstance(presets, dict):
            raise ValueError(f"Invalid presets in {p.name}")
        for mode, mobj in presets.items():
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", mode):
                raise ValueError(f"Invalid preset mode name {mode!r} in {p.name}")
            if not isinstance(mobj, dict):
                raise ValueError(f"Invalid preset mode {mode} in {p.name}")
            for hint, hobj in mobj.items():
                if not isinstance(hobj, dict):
                    raise ValueError(f"Invalid preset hint {mode}.{hint} in {p.name}")
                for k, v in hobj.items():
                    if k.startswith("sysfs.") and not validate_sysfs_val(v):
                        raise ValueError(f"Unsafe presets.{mode}.{hint}.{k}={v} in {p.name}")


def normalize_lf() -> None:
    for p in sorted(MODULE_DIR.rglob("*")):
        if p.is_file() and (p.suffix in TEXT_SUFFIXES or p.name in {"update-binary", "updater-script"}):
            raw = p.read_bytes()
            norm = raw.replace(b"\r\n", b"\n")
            if raw != norm:
                p.write_bytes(norm)


def build_zip(sync_asset: bool = True) -> None:
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    sums = []
    with zipfile.ZipFile(OUT_ZIP, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for p in sorted(MODULE_DIR.rglob("*")):
            if not p.is_file():
                continue
            rel = p.relative_to(MODULE_DIR).as_posix()
            data = p.read_bytes()
            zi = zipfile.ZipInfo(rel, date_time=(2026, 11, 20, 0, 0, 0))
            zi.create_system = 3  # Pin Unix host metadata deterministically
            zi.compress_type = zipfile.ZIP_DEFLATED
            is_exec = rel.startswith("bin/") or rel.endswith(".sh") or rel.endswith("update-binary")
            mode = 0o755 if is_exec else 0o644
            zi.external_attr = (0o100000 | mode) << 16
            zf.writestr(zi, data)
            sums.append(f"{hashlib.sha256(data).hexdigest()}  {rel}")

    zip_sha = hashlib.sha256(OUT_ZIP.read_bytes()).hexdigest()
    sums.append(f"{zip_sha}  dist/{OUT_ZIP.name}")
    OUT_SUMS.write_text("\n".join(sums) + "\n", encoding="utf-8", newline="\n")
    if sync_asset and APP_ASSET_ZIP.parent.exists():
        shutil.copyfile(OUT_ZIP, APP_ASSET_ZIP)
    print(f"Built {OUT_ZIP} ({OUT_ZIP.stat().st_size} bytes), SHA-256={zip_sha}")


if __name__ == "__main__":
    verify_binaries()
    verify_configs()
    normalize_lf()
    build_zip(sync_asset=True)
