// Copyright 2024-2025, shadow3aaa
// Modified 2026-11-23 for SCENE4R security hardening (sysfs/proc path allowlist & symlink rejection).
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
    collections::{HashMap, hash_map::Entry},
    fs::{self, File, set_permissions},
    io::{self, ErrorKind, prelude::*},
    os::unix::fs::PermissionsExt,
    path::{Component, Path, PathBuf},
};

use anyhow::Result;
use sys_mount::{UnmountFlags, unmount};

#[derive(Debug)]
pub struct FileHandler {
    files: HashMap<PathBuf, File>,
}

impl FileHandler {
    pub fn new() -> Self {
        Self {
            files: HashMap::new(),
        }
    }

    pub fn write_with_workround<P, S>(&mut self, path: P, content: S) -> Result<()>
    where
        P: AsRef<Path>,
        S: AsRef<[u8]>,
    {
        if let Err(e) = self.write(path.as_ref(), content.as_ref()) {
            match e.kind() {
                ErrorKind::PermissionDenied => {
                    Self::verify_allowed_target(path.as_ref())?;
                    set_permissions(path.as_ref(), PermissionsExt::from_mode(0o644))?;
                    self.write(path, content)?;
                    Ok(())
                }
                ErrorKind::InvalidInput => Ok(()),
                _ => Err(e.into()),
            }
        } else {
            Ok(())
        }
    }

    pub fn write<P, S>(&mut self, path: P, content: S) -> io::Result<()>
    where
        P: AsRef<Path>,
        S: AsRef<[u8]>,
    {
        Self::verify_allowed_target(path.as_ref())?;

        match self.files.entry(path.as_ref().to_path_buf()) {
            Entry::Occupied(mut entry) => {
                entry.get_mut().write_all(content.as_ref())?;
            }
            Entry::Vacant(entry) => {
                let _ = unmount(path.as_ref(), UnmountFlags::DETACH);
                set_permissions(path.as_ref(), PermissionsExt::from_mode(0o644))?;
                let mut file = File::create(path)?;
                file.write_all(content.as_ref())?;
                entry.insert(file);
            }
        }

        Ok(())
    }

    fn verify_allowed_target(path: &Path) -> io::Result<()> {
        if path
            .components()
            .any(|c| matches!(c, Component::ParentDir))
        {
            return Err(io::Error::new(
                ErrorKind::InvalidInput,
                "parent traversal not allowed",
            ));
        }

        let s = path.to_string_lossy();
        if !s.starts_with("/sys/devices/system/cpu/") && !s.starts_with("/proc/") {
            return Err(io::Error::new(
                ErrorKind::PermissionDenied,
                format!("path outside sysfs/proc allowlist: {s}"),
            ));
        }

        let meta = fs::symlink_metadata(path)?;
        if meta.file_type().is_symlink() {
            return Err(io::Error::new(
                ErrorKind::PermissionDenied,
                format!("refusing symlink target: {s}"),
            ));
        }

        Ok(())
    }
}
