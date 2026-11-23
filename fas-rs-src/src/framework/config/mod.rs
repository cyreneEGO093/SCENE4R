// Copyright 2024-2025, shadow3aaa
// Modified 2026-11-23 for SCENE4R security hardening (Root-only config reads and strict TOML/bounds validator).
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

mod data;
mod inner;
mod merge;
mod read;

use std::{path::Path, sync::mpsc, thread};

use inner::Inner;
use log::{error, info};
use toml::Value;

use crate::{
    framework::{error::Result, node::Mode},
    security::{MAX_CONFIG_BYTES, read_root_file_to_string},
};
pub use data::{ConfigData, MarginFps, MarginFpsValue, ModeConfig, TemperatureThreshold};
use read::wait_and_read;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TargetFps {
    Value(u32),
    Array(Vec<u32>),
}

#[derive(Debug)]
pub struct Config {
    inner: Inner,
}

impl Config {
    pub fn new<P>(p: P, sp: P) -> Result<Self>
    where
        P: AsRef<Path>,
    {
        let path = p.as_ref();
        let std_path = sp.as_ref();
        let toml_raw = read_root_file_to_string(path, MAX_CONFIG_BYTES)?;
        let toml: ConfigData = toml::from_str(&toml_raw)?;

        let (sx, rx) = mpsc::channel();
        let inner = Inner::new(toml, rx);

        {
            let path = path.to_owned();
            let std_path = std_path.to_owned();

            thread::Builder::new()
                .name("ConfigThread".into())
                .spawn(move || {
                    wait_and_read(&path, &std_path, &sx).unwrap_or_else(|e| error!("{e:#?}"));
                    panic!("An unrecoverable error occurred!");
                })?;
        }

        info!("Config watcher started");

        Ok(Self { inner })
    }

    /// Validate a serialized `games.toml` string against schema and parameter bounds.
    ///
    /// # Errors
    /// Returns a descriptive error string if TOML parsing fails or any field is out of range.
    pub fn validate_toml<S: AsRef<str>>(raw: S) -> std::result::Result<(), String> {
        let cfg: ConfigData =
            toml::from_str(raw.as_ref()).map_err(|e| format!("TOML syntax/schema error: {e}"))?;

        Self::validate_mode("powersave", &cfg.powersave)?;
        Self::validate_mode("balance", &cfg.balance)?;
        Self::validate_mode("performance", &cfg.performance)?;
        Self::validate_mode("fast", &cfg.fast)?;

        for (pkg, val) in &cfg.game_list {
            if pkg.is_empty()
                || pkg.len() > 192
                || !pkg
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b':'))
            {
                return Err(format!("invalid package identifier in [game_list]: {pkg}"));
            }

            match val {
                Value::String(s) if s == "auto" => {}
                Value::Integer(fps) if (10..=240).contains(fps) => {}
                Value::Array(arr) if !arr.is_empty() => {
                    for elem in arr {
                        let Some(fps) = elem.as_integer() else {
                            return Err(format!(
                                "non-integer target fps in array for package {pkg}"
                            ));
                        };
                        if !(10..=240).contains(&fps) {
                            return Err(format!(
                                "out-of-range fps {fps} for package {pkg} (expected 10..=240)"
                            ));
                        }
                    }
                }
                _ => {
                    return Err(format!(
                        "unsupported target fps specification for package {pkg}"
                    ));
                }
            }
        }

        Ok(())
    }

    fn validate_mode(name: &str, mode: &ModeConfig) -> std::result::Result<(), String> {
        let check_margin = |label: &str, v: MarginFpsValue| -> std::result::Result<(), String> {
            let f = f64::from(v);
            if !f.is_finite() || !(0.0..=60.0).contains(&f) {
                return Err(format!(
                    "[{name}].{label} out of range: {f} (expected finite 0.0..=60.0)"
                ));
            }
            Ok(())
        };

        match &mode.margin_fps {
            MarginFps::BaseOnly(base) => check_margin("margin_fps", *base)?,
            MarginFps::Advanced { base, overrides } => {
                check_margin("margin_fps.base", *base)?;
                for (fps_key, ov) in overrides {
                    let fps = fps_key.parse::<f64>().map_err(|_| {
                        format!("[{name}].margin_fps override key must be numeric FPS: {fps_key}")
                    })?;
                    if !fps.is_finite() || !(1.0..=240.0).contains(&fps) {
                        return Err(format!(
                            "[{name}].margin_fps override key out of range: {fps_key}"
                        ));
                    }
                    check_margin(&format!("margin_fps.{fps_key}"), *ov)?;
                }
            }
        }

        if let TemperatureThreshold::Temp(t) = mode.core_temp_thresh
            && !(30_000..=120_000).contains(&t)
        {
            return Err(format!(
                "[{name}].core_temp_thresh out of range: {t} (expected 30000..=120000 mC or \"disabled\")"
            ));
        }

        Ok(())
    }

    pub fn need_fas<S>(&mut self, pkg: S) -> bool
    where
        S: AsRef<str>,
    {
        let pkg = pkg.as_ref();

        self.inner.config().game_list.contains_key(pkg)
            || self.inner.config().scene_game_list.contains(pkg)
    }

    pub fn target_fps<S>(&mut self, pkg: S) -> Option<TargetFps>
    where
        S: AsRef<str>,
    {
        let pkg = pkg.as_ref();
        let pkg = pkg.split(':').next()?;

        self.inner.config().game_list.get(pkg).cloned().map_or_else(
            || {
                if self.inner.config().scene_game_list.contains(pkg) {
                    Some(TargetFps::Array(vec![30, 45, 60, 90, 120, 144]))
                } else {
                    None
                }
            },
            |value| match value {
                Value::Array(arr) => {
                    let mut arr: Vec<_> = arr
                        .iter()
                        .filter_map(toml::Value::as_integer)
                        .map(|i| i as u32)
                        .collect();
                    arr.sort_unstable();
                    Some(TargetFps::Array(arr))
                }
                Value::Integer(i) => Some(TargetFps::Value(i as u32)),
                Value::String(s) => {
                    if s == "auto" {
                        Some(TargetFps::Array(vec![30, 45, 60, 90, 120, 144]))
                    } else {
                        error!("Find target game {pkg} in config, but meet illegal data type");
                        error!("Sugg: try \'{pkg} = \"auto\"\'");
                        None
                    }
                }
                _ => {
                    error!("Find target game {pkg} in config, but meet illegal data type");
                    error!("Sugg: try \'{pkg} = \"auto\"\'");
                    None
                }
            },
        )
    }

    #[must_use]
    pub fn mode_config(&mut self, m: Mode) -> &ModeConfig {
        match m {
            Mode::Powersave => &self.inner.config().powersave,
            Mode::Balance => &self.inner.config().balance,
            Mode::Performance => &self.inner.config().performance,
            Mode::Fast => &self.inner.config().fast,
        }
    }
}
