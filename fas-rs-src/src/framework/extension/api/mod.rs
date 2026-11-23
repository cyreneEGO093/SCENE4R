// Copyright 2024-2025, shadow3aaa
// Modified 2026-11-23 for SCENE4R security hardening (no-op extension triggers; Lua plugins disabled).
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

use super::Extension;

#[allow(dead_code)]
pub trait Api: Send {}

pub const fn trigger_init_cpu_freq(_extension: &Extension) {}

pub const fn trigger_reset_cpu_freq(_extension: &Extension) {}

#[allow(clippy::needless_pass_by_value)]
pub fn trigger_load_fas(_extension: &Extension, _pid: i32, _pkg: String) {}

#[allow(clippy::needless_pass_by_value)]
pub fn trigger_unload_fas(_extension: &Extension, _pid: i32, _pkg: String) {}

pub const fn trigger_start_fas(_extension: &Extension) {}

pub const fn trigger_stop_fas(_extension: &Extension) {}

#[allow(clippy::needless_pass_by_value)]
pub fn trigger_target_fps_change(_extension: &Extension, _target_fps: u32, _pkg: String) {}
