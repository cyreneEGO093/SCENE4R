// Copyright 2024-2025, shadow3aaa
// Modified 2026-11-23 for SCENE4R security hardening (moved node to Root-only /data/adb/fas-rs/node with 0700/0600, symlink checks, and timer reset).
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

mod power_mode;

use std::{
    collections::HashMap,
    fs::{self, Permissions},
    os::unix::fs::PermissionsExt,
    path::Path,
    str::FromStr,
    time::{Duration, Instant},
};

use likely_stable::unlikely;
pub use power_mode::Mode;

use crate::{
    framework::error::{Error, Result},
    security::{
        MAX_NODE_BYTES, NODE_DIR, RUNTIME_DIR, ensure_root_dir, read_root_file_to_string,
        write_root_file_atomic,
    },
};

const REFRESH_TIME: Duration = Duration::from_secs(1);

pub struct Node {
    map: HashMap<String, String>,
    timer: Instant,
}

impl Node {
    pub fn init() -> Result<Self> {
        ensure_root_dir(Path::new(RUNTIME_DIR))?;
        ensure_root_dir(Path::new(NODE_DIR))?;

        let mode_path = Path::new(NODE_DIR).join("mode");
        let preserved = read_root_file_to_string(&mode_path, MAX_NODE_BYTES)
            .ok()
            .and_then(|s| Mode::from_str(s.trim()).ok());

        if let Some(mode) = preserved {
            fs::set_permissions(&mode_path, Permissions::from_mode(0o600))?;
            let mut result = Self {
                map: HashMap::new(),
                timer: Instant::now(),
            };
            result.map.insert("mode".to_string(), mode.to_string());
            Ok(result)
        } else {
            write_root_file_atomic(&mode_path, b"balance\n")?;
            let mut result = Self {
                map: HashMap::new(),
                timer: Instant::now(),
            };
            result.map.insert("mode".to_string(), "balance".to_string());
            Ok(result)
        }
    }

    pub fn get_node<S>(&mut self, id: S) -> Result<String>
    where
        S: AsRef<str>,
    {
        let id = id.as_ref();

        if unlikely(self.timer.elapsed() > REFRESH_TIME) {
            self.refresh()?;
        }

        self.map
            .get(id)
            .cloned()
            .ok_or(Error::NodeNotFound)
    }

    fn refresh(&mut self) -> Result<()> {
        self.timer = Instant::now();
        ensure_root_dir(Path::new(NODE_DIR))?;

        let mode_path = Path::new(NODE_DIR).join("mode");
        let value = read_root_file_to_string(&mode_path, MAX_NODE_BYTES)?;
        let trimmed = value.trim();
        let parsed = Mode::from_str(trimmed)?;
        self.map.insert("mode".to_string(), parsed.to_string());

        Ok(())
    }
}
