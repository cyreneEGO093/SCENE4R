# Third-Party Notices & Attributions

This repository combines and modifies multiple open-source components. Overall repository distribution is governed by the [GNU General Public License v3.0](LICENSE), while individual components preserve their upstream copyright notices and license headers.

## 1. SCENE4R Android Application (`scene4-modern/`)
- **Upstream base**: `helloklf/vtools` (`scene4` branch)
- **License**: GNU General Public License v3.0 ([`scene4-modern/LICENSE`](scene4-modern/LICENSE))
- **Modifications (2026)**: Android 16 (API 36) adaptation, dual-cell battery current/power telemetry, UI reorganization (removing QQ-specific items and merging miscellaneous/additional utilities), integrated Xposed hooks, dual-scheduler live switching & fine-grained tuning UI (`Uperf-R` / `FAS-RS`), security hardening, and Root-retry lifecycle fixes.

## 2. FAS-RS (SCENE4R modify) (`fas-rs-src/` & `fas-rs-module/`)
- **Upstream base**: [`shadow3aaa/fas-rs`](https://github.com/shadow3aaa/fas-rs) (`master` commit `09ba876fcdf378004305f30243e979c7cea346dc`)
- **Vendored dependency**: [`shadow3aaa/frame-analyzer-ebpf`](fas-rs-src/vendor/frame-analyzer/) (`fas-rs-src/vendor/frame-analyzer/`)
- **License**: GNU General Public License v3.0 ([`fas-rs-src/LICENSE`](fas-rs-src/LICENSE), [`fas-rs-src/NOTICES`](fas-rs-src/NOTICES), [`fas-rs-module/LICENSE`](fas-rs-module/LICENSE))
- **Modifications (2026-11-23, GPL-3.0 §5a)**:
  - Confined runtime configuration, `/data/adb/fas-rs/node/mode`, mount masks, and logs to Root-only `0700`/`0600` paths; removed `/sdcard/Android/fas-rs` and `/dev/fas_rs` surfaces.
  - Removed `mlua`/LuaJIT extension runtime (`Cargo.toml`, `misc.rs`, and extension callbacks replaced with no-ops) and unused build dependencies.
  - Added `SIGTERM`/`SIGINT` cleanup handler, `fas-rs validate` config validation CLI, parent-directory `inotify` reload support, and Android 16 `libgui.so` `Surface::queueBuffer(sp<GraphicBuffer>&&, int, SurfaceQueueBufferOutput*)` eBPF uprobe matching in `fas-rs-src/vendor/frame-analyzer/src/uprobe.rs`.

## 3. SCENE4R SWAP Controller (`swap-controller-module/`)
- **Upstream base**: `helloklf/vtools` (`scene_swap_controller`)
- **License**: GNU General Public License v3.0 (`SPDX-License-Identifier: GPL-3.0-or-later`, [`swap-controller-module/LICENSE`](swap-controller-module/LICENSE))
- **Modifications (2026)**: Rebuilt `scene_swap_service.dex` from [`swap-controller-module/src/Main.java`](swap-controller-module/src/Main.java) adding Linux 5.x/6.x `cgroup v2` (`/sys/fs/cgroup/apps/<uid>/<pid>/memory.reclaim`) reclaim support without force-killing tasks or overwriting OEM `perfconfigstore.xml` / LMKD parameters.

## 4. Uperf-R (`uperf-r-module/`)
- **Control & install scripts (`uperf-r-module/script/*.sh`)**:
  - **Upstream base**: `yc9559/uperf` (`dev-22.09.04`)
  - **License**: Apache License 2.0 (Copyright 2021-2022 Matt Yang; preserved in script headers)
  - **Modifications (2026)**: Isolated runtime files under `/data/adb/uperf` (`0700`/`0600`), added journaled pre-write rollback verification, strict 42-path `ALLOWED_KNOB_PATHS` / `validate_config.awk` validation, 16 KiB kernel page-size guard, and removal of OEM thermal-disable / A-SOUL / intrusive system property scripts.
- **SoC configuration profiles (`uperf-r-module/config/*.json`)**:
  - Incorporates Snapdragon 8 Gen 3 tuning parameters from `yinwanxi/Uperf-Game-Turbo` (`1.51`), normalized to `/data/adb/uperf` and filtered to `ALLOWED_KNOB_PATHS`.
- **Prebuilt scheduler binary (`uperf-r-module/bin/uperf`)**:
  - Precompiled upstream userspace binary from `yc9559/uperf` (pinned SHA-256: `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8`). Note: its `PT_LOAD` segments are 4 KiB-aligned (`0x1000`), so `libuperf.sh` / `initsvc.sh` refuse startup on 16 KiB page-size kernels.

## 5. Prebuilt BusyBox & Toybox Utilities
- **BusyBox (`1.30.1`, GNU GPL v2.0, <https://busybox.net/>)**:
  - `arm64-v8a`: `scene4-modern/app/src/main/jniLibs/arm64-v8a/libbusybox.so`, `scene4-modern/app/src/main/assets/toolkit/busybox64`, `uperf-r-module/bin/busybox/busybox` (2,054,232 bytes, SHA-256: `f03b1cda5b19da390d96befc1e26661bcf6570fd940ff55537ff113825b38dc9`)
  - `armeabi-v7a`: `scene4-modern/app/src/main/jniLibs/armeabi-v7a/libbusybox.so`, `scene4-modern/app/src/main/assets/toolkit/busybox` (1,333,960 bytes, SHA-256: `29b8083206a76bde2314a84d16694263c678d6baf69422e707e70b756e34b93d`)
- **Toybox prebuilts** (`scene4-modern/app/src/main/assets/toolkit/toybox-outside*`, 0BSD / Toybox License; verified by exact SHA-256 in `ToyboxIntaller.kt`).
- **Build-time dependencies**: AndroidX, Material Components, Kotlin Coroutines, and compile-only Xposed API (`de.robv.android.xposed:api:82`) declared in [`scene4-modern/app/build.gradle`](scene4-modern/app/build.gradle) are resolved from upstream Maven repositories at build time.
