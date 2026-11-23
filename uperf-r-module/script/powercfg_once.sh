#!/system/bin/sh
#
# Copyright (C) 2021-2022 Matt Yang
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Thermal-preserving & non-destructive baseline initialization.
# Does NOT disable msm_thermal, mi_thermald, thermal-hal, battery OC,
# PPM throttling policies, msm_performance limits, KGSL/bus limits, or vendor PowerHAL.

BASEDIR="$(dirname "$(readlink -f "$0")")"
. "$BASEDIR/pathinfo.sh"
. "$BASEDIR/libcommon.sh"
. "$BASEDIR/libcgroup.sh"

unify_cgroup() {
    if [ -f /dev/cpuset/top-app/tasks ] && [ -f /dev/cpuset/foreground/tasks ] && [ ! -L /dev/cpuset/foreground/tasks ]; then
        for p in $(cat /dev/cpuset/top-app/tasks 2>/dev/null); do
            echo "$p" >/dev/cpuset/foreground/tasks 2>/dev/null
        done
    fi

    # Place background media/display housekeeping out of top-app cpuset cleanly
    change_task_cgroup "surfaceflinger" "" "cpuset"
    change_task_cgroup "system_server" "foreground" "cpuset"
    change_task_cgroup "netd|allocator" "foreground" "cpuset"
    change_task_cgroup "hardware.media.c2|vendor.mediatek.hardware" "background" "cpuset"
}

clear_log || exit 1
exec 1>"$LOG_FILE"
echo "PATH=$PATH"
echo "sh=$(which sh)"
echo "thermal_policy=strict_oem_preserved"
echo "hal_policy=oem_preserved"

rebuild_process_scan_cache
unify_cgroup
exit 0
