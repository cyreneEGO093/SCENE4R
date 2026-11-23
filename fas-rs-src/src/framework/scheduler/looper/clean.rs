// Copyright 2024-2025, shadow3aaa
// Modified 2026-11-23 for SCENE4R security hardening (Root-only mount masks under /data/adb/fas-rs/.mount_masks, symlink checks, detached unmount).
//
// This file is part of fas-rs.
//
// fas-rs is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.
//
// fas-rs is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along
// with fas-rs. If not, see <https://www.gnu.org/licenses/>.

use std::{
    collections::HashMap,
    ffi::CString,
    fs::{self, set_permissions},
    os::unix::fs::PermissionsExt,
    path::Path,
    ptr,
};

use libc::{MNT_DETACH, MS_BIND, MS_REC, mount, umount2};

use crate::{
    framework::error::Result,
    security::{MOUNT_MASK_DIR, ensure_root_dir, write_root_file_atomic},
};

fn lock_value<P, S>(path: P, value: S)
where
    P: AsRef<Path>,
    S: AsRef<str>,
{
    let value = value.as_ref();
    if value != "0" && value != "1" {
        return;
    }

    let path = path.as_ref();
    let Ok(meta) = fs::symlink_metadata(path) else {
        return;
    };
    if meta.file_type().is_symlink() {
        return;
    }

    let mask_dir = Path::new(MOUNT_MASK_DIR);
    if ensure_root_dir(mask_dir).is_err() {
        return;
    }

    let path_str = path.display().to_string();
    let mount_path = mask_dir.join(format!("mask_{value}"));
    if write_root_file_atomic(&mount_path, value.as_bytes()).is_err() {
        return;
    }

    let _ = unmount(&path_str);
    let _ = set_permissions(path, PermissionsExt::from_mode(0o644));
    let _ = fs::write(&path_str, value);
    let _ = set_permissions(path, PermissionsExt::from_mode(0o444));
    let _ = mount_bind(&mount_path.display().to_string(), &path_str);
}

fn mount_bind(src_path: &str, dest_path: &str) -> Result<()> {
    let src_path = CString::new(src_path)?;
    let dest_path = CString::new(dest_path)?;

    unsafe {
        umount2(dest_path.as_ptr(), MNT_DETACH);

        if mount(
            src_path.as_ptr().cast(),
            dest_path.as_ptr().cast(),
            ptr::null(),
            MS_BIND | MS_REC,
            ptr::null(),
        ) != 0
        {
            return Err(std::io::Error::last_os_error().into());
        }
    }

    Ok(())
}

fn unmount(file_system: &str) -> Result<()> {
    let path = CString::new(file_system)?;
    if unsafe { umount2(path.as_ptr(), MNT_DETACH) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(())
}

macro_rules! lock_values {
    ($map: expr, ($($path: literal),*), $value: literal) => {
        $({
            let target = Path::new($path);
            if let Ok(meta) = fs::symlink_metadata(target)
                && !meta.file_type().is_symlink()
            {
                if let Ok(last_value) = fs::read_to_string(target) {
                    $map.insert($path, last_value);
                }

                lock_value(target, $value);
            }
        })*
    }
}

pub struct Cleaner {
    map: HashMap<&'static str, String>,
}

impl Cleaner {
    pub fn new() -> Self {
        Self {
            map: HashMap::new(),
        }
    }

    pub fn cleanup(&mut self) {
        lock_values!(
            self.map,
            (
                "/sys/module/mtk_fpsgo/parameters/perfmgr_enable",
                "/sys/module/perfmgr/parameters/perfmgr_enable",
                "/sys/module/perfmgr_policy/parameters/perfmgr_enable",
                "/sys/module/perfmgr_mtk/parameters/perfmgr_enable",
                "/sys/module/migt/parameters/glk_fbreak_enable"
            ),
            "0"
        );

        lock_values!(
            self.map,
            (
                "/sys/module/migt/parameters/glk_disable",
                "/proc/game_opt/disable_cpufreq_limit"
            ),
            "1"
        );
    }

    pub fn undo_cleanup(&mut self) {
        for (path, value) in self.map.drain() {
            let _ = unmount(path);
            if let Ok(meta) = fs::symlink_metadata(path)
                && !meta.file_type().is_symlink()
            {
                let _ = set_permissions(path, PermissionsExt::from_mode(0o644));
                if !value.is_empty() {
                    let _ = fs::write(path, value);
                }
            }
        }
    }
}
