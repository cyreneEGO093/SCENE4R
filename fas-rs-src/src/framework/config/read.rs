// Copyright 2024-2025, shadow3, shadow3aaa
// Modified 2026-11-23 for SCENE4R security hardening (Root-only config/scene_games reads + atomic rename inotify reload).
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

use std::{path::Path, sync::mpsc::Sender, time::Duration};

use inotify::{Inotify, WatchMask};
use log::{debug, error, info};

use super::data::{ConfigData, SceneAppList};
use crate::{
    framework::error::Result,
    security::{MAX_CONFIG_BYTES, RUNTIME_DIR, SCENE_PROFILE, ensure_root_dir, read_root_file_to_string},
};

const MAX_RETRY_COUNT: u8 = 10;

pub(super) fn wait_and_read(path: &Path, std_path: &Path, sx: &Sender<ConfigData>) -> Result<()> {
    let std_config = read_config(std_path)?;
    let mut first_load = true;

    loop {
        match read_config_with_retry(path) {
            Ok(mut config) => {
                if config.config.scene_game_list
                    && let Err(e) = read_scene_games(&mut config)
                {
                    error!("Failed to read scene games: {e}");
                }
                if sx.send(config).is_err() {
                    return Ok(());
                }
                if !first_load {
                    info!("Reloaded config from {}", path.display());
                }
                first_load = false;
            }
            Err(e) => {
                error!("Too many retries reading config: {e}");
                error!("Using standard profile until user config is available.");
                if sx.send(std_config.clone()).is_err() {
                    return Ok(());
                }
                first_load = false;
            }
        }

        wait_until_update()?;
    }
}

fn read_config(path: &Path) -> Result<ConfigData> {
    let content = read_root_file_to_string(path, MAX_CONFIG_BYTES)?;
    let config = toml::from_str(&content)?;
    Ok(config)
}

fn read_config_with_retry(path: &Path) -> Result<ConfigData> {
    let mut retry_count = 0;

    loop {
        match read_config(path) {
            Ok(config) => return Ok(config),
            Err(e) => {
                debug!("Failed to read config at {}: {e}", path.display());
                retry_count += 1;
                if retry_count >= MAX_RETRY_COUNT {
                    return Err(e);
                }
                std::thread::sleep(Duration::from_secs(1));
            }
        }
    }
}

fn read_scene_games(config: &mut ConfigData) -> Result<()> {
    let profile_path = Path::new(SCENE_PROFILE);
    if profile_path.exists() {
        let scene_apps = read_root_file_to_string(profile_path, MAX_CONFIG_BYTES)?;
        let scene_apps: SceneAppList = quick_xml::de::from_str(&scene_apps)?;
        let game_list = scene_apps
            .apps
            .into_iter()
            .filter(|app| app.is_game && is_safe_pkg(&app.pkg))
            .map(|game| game.pkg)
            .collect();

        config.scene_game_list = game_list;
    }

    Ok(())
}

fn is_safe_pkg(pkg: &str) -> bool {
    !pkg.is_empty()
        && pkg.len() <= 192
        && pkg
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b':'))
}

fn wait_until_update() -> Result<()> {
    let runtime_dir = Path::new(RUNTIME_DIR);
    ensure_root_dir(runtime_dir)?;

    let mut inotify = Inotify::init()?;
    inotify.watches().add(
        runtime_dir,
        WatchMask::CLOSE_WRITE | WatchMask::MOVED_TO | WatchMask::CREATE,
    )?;

    let mut buffer = [0; 4096];
    loop {
        let events = inotify.read_events_blocking(&mut buffer)?;
        for event in events {
            if let Some(name) = event.name.and_then(|n| n.to_str())
                && (name == "games.toml" || name == "scene_games.xml")
            {
                return Ok(());
            }
        }
    }
}
