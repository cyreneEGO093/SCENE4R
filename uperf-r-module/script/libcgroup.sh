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

# process scan cache
ps_ret=""

# $1:task_name $2:cgroup_name $3:"cpuset"/"stune"
change_task_cgroup() {
    [ -d "/dev/$3/$2" ] || return 0
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        [ -d "/proc/$temp_pid/task" ] || continue
        for temp_tid in $(ls "/proc/$temp_pid/task/" 2>/dev/null); do
            echo "$temp_tid" >"/dev/$3/$2/tasks" 2>/dev/null
        done
    done
}

# $1:process_name $2:cgroup_name $3:"cpuset"/"stune"
change_proc_cgroup() {
    [ -d "/dev/$3/$2" ] || return 0
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        echo "$temp_pid" >"/dev/$3/$2/cgroup.procs" 2>/dev/null
    done
}

# $1:task_name $2:thread_name $3:cgroup_name $4:"cpuset"/"stune"
change_thread_cgroup() {
    [ -d "/dev/$4/$3" ] || return 0
    local comm
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        [ -d "/proc/$temp_pid/task" ] || continue
        for temp_tid in $(ls "/proc/$temp_pid/task/" 2>/dev/null); do
            comm="$(cat "/proc/$temp_pid/task/$temp_tid/comm" 2>/dev/null)"
            if echo "$comm" | grep -qiE "$2"; then
                echo "$temp_tid" >"/dev/$4/$3/tasks" 2>/dev/null
            fi
        done
    done
}

# $1:task_name $2:cgroup_name $3:"cpuset"/"stune"
change_main_thread_cgroup() {
    [ -d "/dev/$3/$2" ] || return 0
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        echo "$temp_pid" >"/dev/$3/$2/tasks" 2>/dev/null
    done
}

# $1:task_name $2:hex_mask
change_task_affinity() {
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        [ -d "/proc/$temp_pid/task" ] || continue
        for temp_tid in $(ls "/proc/$temp_pid/task/" 2>/dev/null); do
            taskset -p "$2" "$temp_tid" >>"$LOG_FILE" 2>&1
        done
    done
}

# $1:task_name $2:thread_name $3:hex_mask
change_thread_affinity() {
    local comm
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        [ -d "/proc/$temp_pid/task" ] || continue
        for temp_tid in $(ls "/proc/$temp_pid/task/" 2>/dev/null); do
            comm="$(cat "/proc/$temp_pid/task/$temp_tid/comm" 2>/dev/null)"
            if echo "$comm" | grep -qiE "$2"; then
                taskset -p "$3" "$temp_tid" >>"$LOG_FILE" 2>&1
            fi
        done
    done
}

# $1:task_name $2:nice(relative to 120)
change_task_nice() {
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        [ -d "/proc/$temp_pid/task" ] || continue
        for temp_tid in $(ls "/proc/$temp_pid/task/" 2>/dev/null); do
            renice -n +40 -p "$temp_tid" 2>/dev/null
            renice -n -19 -p "$temp_tid" 2>/dev/null
            renice -n "$2" -p "$temp_tid" 2>/dev/null
        done
    done
}

# $1:task_name $2:thread_name $3:nice(relative to 120)
change_thread_nice() {
    local comm
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        [ -d "/proc/$temp_pid/task" ] || continue
        for temp_tid in $(ls "/proc/$temp_pid/task/" 2>/dev/null); do
            comm="$(cat "/proc/$temp_pid/task/$temp_tid/comm" 2>/dev/null)"
            if echo "$comm" | grep -qiE "$2"; then
                renice -n +40 -p "$temp_tid" 2>/dev/null
                renice -n -19 -p "$temp_tid" 2>/dev/null
                renice -n "$3" -p "$temp_tid" 2>/dev/null
            fi
        done
    done
}

# $1:task_name $2:priority
change_task_rt() {
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        [ -d "/proc/$temp_pid/task" ] || continue
        for temp_tid in $(ls "/proc/$temp_pid/task/" 2>/dev/null); do
            chrt -f -p "$2" "$temp_tid" >>"$LOG_FILE" 2>&1
        done
    done
}

# $1:task_name $2:thread_name $3:priority
change_thread_rt() {
    local comm
    for temp_pid in $(echo "$ps_ret" | grep -i -E "$1" | awk '{print $1}'); do
        [ -d "/proc/$temp_pid/task" ] || continue
        for temp_tid in $(ls "/proc/$temp_pid/task/" 2>/dev/null); do
            comm="$(cat "/proc/$temp_pid/task/$temp_tid/comm" 2>/dev/null)"
            if echo "$comm" | grep -qiE "$2"; then
                chrt -f -p "$3" "$temp_tid" >>"$LOG_FILE" 2>&1
            fi
        done
    done
}

rebuild_process_scan_cache() {
    ps_ret="$(ps -Ao pid,args 2>/dev/null)"
}
