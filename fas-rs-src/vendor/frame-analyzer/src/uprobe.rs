/*
 * Copyright (c) 2024 shadow3aaa@gitbub.com
 * Modified 2026-11-23 for SCENE4R Android 16 (API 36) libgui.so Surface::queueBuffer uprobe compatibility.
 *
 * This file is part of frame-analyzer-ebpf.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
use aya::{
    Ebpf,
    maps::{MapData, RingBuf},
    programs::UProbe,
};

use crate::{ebpf::load_bpf, error::Result};

const QUEUE_BUFFER_SYMBOLS: &[&str] = &[
    // Android 16+ (API 36): virtual Surface::queueBuffer(sp<GraphicBuffer>&&, int, SurfaceQueueBufferOutput*)
    "_ZN7android7Surface11queueBufferEONS_2spINS_13GraphicBufferEEEiPNS_24SurfaceQueueBufferOutputE",
    // Android 15 (API 35): Surface::queueBuffer(ANativeWindowBuffer*, int, SurfaceQueueBufferOutput*)
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferiPNS_24SurfaceQueueBufferOutputE",
    // Android 14 and earlier: Surface::queueBuffer(ANativeWindowBuffer*, int)
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferi",
    // Fallbacks if virtual overload is inlined/renamed on custom ROMs
    "_ZN7android7Surface16hook_queueBufferEP13ANativeWindowP19ANativeWindowBufferi",
    "_ZN7android7Surface19queueBufferInternalEP13ANativeWindowP19ANativeWindowBufferi",
];

pub struct UprobeHandler {
    bpf: Ebpf,
}

impl Drop for UprobeHandler {
    fn drop(&mut self) {
        if let Ok(program) = self.get_program() {
            let _ = program.unload();
        }
    }
}

impl UprobeHandler {
    pub fn attach_app(pid: i32) -> Result<Self> {
        let mut bpf = load_bpf()?;

        {
            let program: &mut UProbe = bpf.program_mut("frame_analyzer_ebpf").unwrap().try_into()?;
            program.load()?;

            let mut last_err = None;
            let mut attached = false;
            for sym in QUEUE_BUFFER_SYMBOLS {
                match program.attach(Some(sym), 0, "/system/lib64/libgui.so", Some(pid)) {
                    Ok(_) => {
                        attached = true;
                        break;
                    }
                    Err(e) => last_err = Some(e),
                }
            }
            if !attached {
                return Err(last_err.unwrap().into());
            }
        }

        Ok(Self { bpf })
    }

    pub fn ring(&mut self) -> Result<RingBuf<&mut MapData>> {
        let ring: RingBuf<&mut MapData> = RingBuf::try_from(self.bpf.map_mut("RING_BUF").unwrap())?;
        Ok(ring)
    }

    fn get_program(&mut self) -> Result<&mut UProbe> {
        let program: &mut UProbe = self
            .bpf
            .program_mut("frame_analyzer_ebpf")
            .unwrap()
            .try_into()?;
        Ok(program)
    }
}
