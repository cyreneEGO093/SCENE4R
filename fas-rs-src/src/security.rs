// Copyright 2026 SCENE4R Contributors
// Modified 2026-11-23 for SCENE4R security hardening (Root-exclusive /data/adb/fas-rs trust boundary).
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
    fs::{self, OpenOptions, Permissions},
    io::{self, ErrorKind, Read, Write},
    os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt},
    path::Path,
    process,
};

pub const RUNTIME_DIR: &str = "/data/adb/fas-rs";
pub const USER_CONFIG: &str = "/data/adb/fas-rs/games.toml";
pub const NODE_DIR: &str = "/data/adb/fas-rs/node";
pub const MOUNT_MASK_DIR: &str = "/data/adb/fas-rs/.mount_masks";
pub const SCENE_PROFILE: &str = "/data/adb/fas-rs/scene_games.xml";
pub const MAX_CONFIG_BYTES: u64 = 1024 * 1024;
pub const MAX_NODE_BYTES: u64 = 64;

/// Ensure `path` is a root-owned non-symlink directory with mode `0700`.
///
/// # Errors
/// Returns an `io::Error` if any ancestor or target is a symlink, not owned by root,
/// not a directory, or permissions cannot be enforced.
pub fn ensure_root_dir(path: &Path) -> io::Result<()> {
    let adb_dir = Path::new("/data/adb");
    if path.starts_with(adb_dir) {
        match fs::symlink_metadata(adb_dir) {
            Ok(meta) => {
                if meta.file_type().is_symlink() || !meta.is_dir() || meta.uid() != 0 {
                    return Err(io::Error::new(
                        ErrorKind::PermissionDenied,
                        "unsafe /data/adb parent directory",
                    ));
                }
            }
            Err(e) if e.kind() == ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
    }

    match fs::symlink_metadata(path) {
        Ok(meta) => {
            if meta.file_type().is_symlink() || !meta.is_dir() {
                return Err(io::Error::new(
                    ErrorKind::PermissionDenied,
                    format!("unsafe non-dir or symlink at {}", path.display()),
                ));
            }
        }
        Err(e) if e.kind() == ErrorKind::NotFound => {
            fs::create_dir_all(path)?;
        }
        Err(e) => return Err(e),
    }

    let meta = fs::symlink_metadata(path)?;
    if meta.file_type().is_symlink() || !meta.is_dir() || meta.uid() != 0 {
        return Err(io::Error::new(
            ErrorKind::PermissionDenied,
            format!("directory {} must be owned by root", path.display()),
        ));
    }

    fs::set_permissions(path, Permissions::from_mode(0o700))?;
    Ok(())
}

/// Verify `path` is a non-symlink regular file owned by uid 0 within `max_bytes`.
///
/// # Errors
/// Returns an `io::Error` on symlink, non-regular file, non-root owner, or size exceeded.
pub fn verify_root_regular_file(path: &Path, max_bytes: u64) -> io::Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if meta.file_type().is_symlink() || !meta.is_file() {
        return Err(io::Error::new(
            ErrorKind::PermissionDenied,
            format!("refusing symlink or non-regular file {}", path.display()),
        ));
    }
    if meta.uid() != 0 {
        return Err(io::Error::new(
            ErrorKind::PermissionDenied,
            format!("file {} not owned by root", path.display()),
        ));
    }
    if meta.len() > max_bytes {
        return Err(io::Error::new(
            ErrorKind::InvalidData,
            format!("file {} exceeds max size {max_bytes}", path.display()),
        ));
    }
    Ok(())
}

/// Safely read a root-owned regular file with `O_NOFOLLOW` and bounded length.
///
/// # Errors
/// Returns an `io::Error` on trust-boundary violation, size overflow, or read error.
pub fn read_root_file_to_string(path: &Path, max_bytes: u64) -> io::Result<String> {
    verify_root_regular_file(path, max_bytes)?;

    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)?;

    let meta = file.metadata()?;
    if !meta.is_file() || meta.uid() != 0 || meta.len() > max_bytes {
        return Err(io::Error::new(
            ErrorKind::PermissionDenied,
            format!("file metadata changed unexpectedly: {}", path.display()),
        ));
    }

    let mut content = String::new();
    file.take(max_bytes + 1).read_to_string(&mut content)?;
    if (content.len() as u64) > max_bytes {
        return Err(io::Error::new(
            ErrorKind::InvalidData,
            format!("file {} exceeded bounded read limit", path.display()),
        ));
    }

    Ok(content)
}

/// Atomically write a root-only `0600` regular file inside a validated parent directory.
///
/// # Errors
/// Returns an `io::Error` on symlink, unsafe parent directory, or write/rename error.
pub fn write_root_file_atomic(path: &Path, content: &[u8]) -> io::Result<()> {
    let Some(parent) = path.parent() else {
        return Err(io::Error::new(ErrorKind::InvalidInput, "missing parent dir"));
    };
    ensure_root_dir(parent)?;

    if let Ok(meta) = fs::symlink_metadata(path)
        && (meta.file_type().is_symlink() || !meta.is_file())
    {
        return Err(io::Error::new(
            ErrorKind::PermissionDenied,
            format!("refusing to overwrite symlink or non-file {}", path.display()),
        ));
    }

    let name = path
        .file_name()
        .and_then(|s| s.to_str())
        .ok_or_else(|| io::Error::new(ErrorKind::InvalidInput, "invalid file name"))?;
    let tmp_path = parent.join(format!(".{name}.tmp.{}", process::id()));

    if let Ok(meta) = fs::symlink_metadata(&tmp_path) {
        if meta.file_type().is_symlink() {
            return Err(io::Error::new(
                ErrorKind::PermissionDenied,
                "symlink at temp path",
            ));
        }
        let _ = fs::remove_file(&tmp_path);
    }

    {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .custom_flags(libc::O_NOFOLLOW)
            .mode(0o600)
            .open(&tmp_path)?;
        file.write_all(content)?;
        file.sync_all()?;
    }

    fs::set_permissions(&tmp_path, Permissions::from_mode(0o600))?;
    fs::rename(&tmp_path, path)?;
    Ok(())
}
