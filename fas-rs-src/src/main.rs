// Copyright 2023-2025, shadow3, shadow3aaa
// Modified 2026-11-23 for SCENE4R security hardening (Root-exclusive /data/adb/fas-rs, umask 077, validate subcommand, SIGTERM clean exit, removed setprop & Lua hooks).
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

#![deny(clippy::all, clippy::pedantic)]
#![warn(clippy::nursery)]
#![allow(
    clippy::module_name_repetitions,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    clippy::cast_possible_wrap
)]

mod cpu_common;
mod file_handler;
mod framework;
pub mod security;

use std::{
    env, fs,
    io::{self, prelude::*},
    path::Path,
    process,
    sync::atomic::{AtomicBool, Ordering},
};

use anyhow::Result;
use flexi_logger::{DeferredNow, LogSpecification, Logger, Record};
use log::error;
use mimalloc::MiMalloc;

#[cfg(debug_assertions)]
use log::debug;

use cpu_common::Controller;
use framework::prelude::*;
use security::{MAX_CONFIG_BYTES, RUNTIME_DIR, USER_CONFIG};

#[global_allocator]
static GLOBAL: MiMalloc = MiMalloc;

static SHUTDOWN_REQUESTED: AtomicBool = AtomicBool::new(false);

extern "C" fn handle_shutdown_signal(_sig: libc::c_int) {
    SHUTDOWN_REQUESTED.store(true, Ordering::SeqCst);
}

#[must_use]
pub fn shutdown_requested() -> bool {
    SHUTDOWN_REQUESTED.load(Ordering::SeqCst)
}

fn install_signal_handlers() {
    unsafe {
        let handler = handle_shutdown_signal as *const () as libc::sighandler_t;
        libc::signal(libc::SIGTERM, handler);
        libc::signal(libc::SIGINT, handler);
    }
}

fn main() -> Result<()> {
    unsafe {
        libc::umask(0o077);
    }
    install_signal_handlers();

    let args: Vec<String> = env::args().collect();
    if args.len() != 3 {
        eprintln!("Usage: fas-rs <run|merge|validate> <config_path>");
        process::exit(2);
    }

    match args[1].as_str() {
        "merge" => {
            let local = security::read_root_file_to_string(Path::new(USER_CONFIG), MAX_CONFIG_BYTES)?;
            let std = security::read_root_file_to_string(Path::new(&args[2]), MAX_CONFIG_BYTES)?;
            let new = Config::merge(&local, &std).unwrap_or(std);
            println!("{new}");
        }
        "validate" => {
            let raw = match security::read_root_file_to_string(Path::new(&args[2]), MAX_CONFIG_BYTES) {
                Ok(content) => content,
                Err(e) => {
                    eprintln!("VALIDATION_ERROR: {e}");
                    process::exit(1);
                }
            };
            if let Err(msg) = Config::validate_toml(&raw) {
                eprintln!("VALIDATION_ERROR: {msg}");
                process::exit(1);
            }
            println!("VALID_TOML");
        }
        "run" => {
            if let Err(e) = run(&args[2]) {
                for cause in e.chain() {
                    error!("{cause:#?}");
                }
                error!("{:#?}", e.backtrace());
                process::exit(1);
            }
        }
        other => {
            eprintln!("Unknown command: {other}");
            eprintln!("Usage: fas-rs <run|merge|validate> <config_path>");
            process::exit(2);
        }
    }

    Ok(())
}

fn run<S>(std_path: S) -> Result<()>
where
    S: AsRef<str>,
{
    #[cfg(not(debug_assertions))]
    let logger_spec = LogSpecification::info();

    #[cfg(debug_assertions)]
    let logger_spec = LogSpecification::debug();

    Logger::with(logger_spec)
        .log_to_stdout()
        .format(log_format)
        .start()?;

    let std_path = std_path.as_ref();
    security::ensure_root_dir(Path::new(RUNTIME_DIR))?;

    let self_pid = process::id();
    let _ = fs::write("/dev/cpuset/background/cgroup.procs", self_pid.to_string());

    let config = Config::new(USER_CONFIG, std_path)?;
    let cpu = Controller::new()?;

    #[cfg(debug_assertions)]
    debug!("{cpu:#?}");

    Scheduler::new()
        .config(config)
        .controller(cpu)
        .start_run()?;

    Ok(())
}

fn log_format(
    write: &mut dyn Write,
    now: &mut DeferredNow,
    record: &Record<'_>,
) -> Result<(), io::Error> {
    let time = now.format("%Y-%m-%d %H:%M:%S");
    write!(write, "[{time}] {}: {}", record.level(), record.args())
}
