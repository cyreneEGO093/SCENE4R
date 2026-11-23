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

BASEDIR="$(dirname "$(readlink -f "$0")")"
. "$BASEDIR/pathinfo.sh"
. "$BASEDIR/libcommon.sh"
. "$BASEDIR/libcgroup.sh"

uperf_stop() {
    killall uperf 2>/dev/null
}

ensure_runtime_dir() {
    [ -L "/data/adb" ] && return 1
    [ -L "$USER_PATH" ] && return 1
    mkdir -p "$USER_PATH" || return 1
    [ -d "$USER_PATH" ] && [ ! -L "$USER_PATH" ] || return 1
    chown 0:0 "$USER_PATH" || return 1
    chmod 0700 "$USER_PATH" || return 1
}

ensure_safe_file() {
    local f="$1"
    [ -e "$f" ] && [ -L "$f" ] && return 1
    [ -f "$f" ] || return 1
    chown 0:0 "$f" || return 1
    chmod 0600 "$f" || return 1
}

check_page_size_compat() {
    local psize rc
    psize="$(getconf PAGESIZE 2>/dev/null || getconf PAGE_SIZE 2>/dev/null)"
    rc=$?
    # Strictly fail closed: probe must succeed cleanly AND output exact "4096"
    if [ "$rc" -ne 0 ] || [ "$psize" != "4096" ]; then
        log "ERROR: Kernel PAGESIZE check failed closed (rc=$rc, value='$psize'; requires exact 4096)."
        return 1
    fi
    return 0
}

verify_knob_targets_safe() {
    local knobs_file="$1"
    while IFS= read -r p; do
        [ -n "$p" ] || continue
        if [ -L "$p" ]; then
            log "ERROR: device knob path is a symlink: $p"
            return 1
        fi
        if [ -e "$p" ]; then
            local real_p
            real_p="$(readlink -f "$p" 2>/dev/null)" || { log "ERROR: canonicalization failed: $p"; return 1; }
            case "$real_p" in
            /sys/* | /dev/cpuset/* | /proc/cpufreq/*) ;;
            *)
                log "ERROR: knob target escapes allowed sysfs/cpuset tree: $p -> $real_p"
                return 1
                ;;
            esac
        fi
    done <"$knobs_file"
}

validate_active_config() {
    local cfg="$USER_PATH/uperf.json"
    ensure_safe_file "$cfg" || return 1
    local bb="$BIN_PATH/busybox/busybox"
    [ -x "$bb" ] && [ ! -L "$bb" ] || return 1
    local knobs_tmp
    knobs_tmp="$("$bb" mktemp "$USER_PATH/.start_knobs.XXXXXX" 2>/dev/null)" || return 1
    chmod 0600 "$knobs_tmp" || { rm -f "$knobs_tmp"; return 1; }
    if ! "$bb" awk -v KNOBS_OUT="$knobs_tmp" -f "$BASEDIR/validate_config.awk" "$cfg"; then
        rm -f "$knobs_tmp"
        return 1
    fi
    verify_knob_targets_safe "$knobs_tmp"
    local rc=$?
    rm -f "$knobs_tmp"
    return "$rc"
}

backup_inotify_limits() {
    [ -L "$BACKUP_PATH" ] && return 1
    mkdir -p "$BACKUP_PATH" && chown 0:0 "$BACKUP_PATH" && chmod 0700 "$BACKUP_PATH" || return 1
    local bak="$BACKUP_PATH/inotify.bak"
    [ -L "$bak" ] && return 1
    if [ ! -f "$bak" ]; then
        local bb="$BIN_PATH/busybox/busybox"
        local tmp_in
        tmp_in="$("$bb" mktemp "$BACKUP_PATH/.inotify_tmp.XXXXXX" 2>/dev/null)" || return 1
        chmod 0600 "$tmp_in" || { rm -f "$tmp_in"; return 1; }
        for k in /proc/sys/fs/inotify/max_queued_events /proc/sys/fs/inotify/max_user_watches /proc/sys/fs/inotify/max_user_instances; do
            if [ -f "$k" ] && [ ! -L "$k" ]; then
                local val uid gid mode
                val="$(cat "$k" 2>/dev/null)" || { rm -f "$tmp_in"; return 1; }
                uid="$(stat -c '%u' "$k" 2>/dev/null)" || { rm -f "$tmp_in"; return 1; }
                gid="$(stat -c '%g' "$k" 2>/dev/null)" || { rm -f "$tmp_in"; return 1; }
                mode="$(stat -c '%a' "$k" 2>/dev/null)" || { rm -f "$tmp_in"; return 1; }
                [ -n "$val" ] && [ -n "$uid" ] && [ -n "$gid" ] && [ -n "$mode" ] || { rm -f "$tmp_in"; return 1; }
                printf '%s\t%s\t%s\t%s\t%s\n' "$k" "$val" "$uid" "$gid" "$mode" >>"$tmp_in" || { rm -f "$tmp_in"; return 1; }
            fi
        done
        chown 0:0 "$tmp_in" && chmod 0600 "$tmp_in" && mv -f "$tmp_in" "$bak" || { rm -f "$tmp_in"; return 1; }
    fi
}

uperf_start() {
    ensure_runtime_dir || return 1
    check_page_size_compat || return 1

    local bin="$BIN_PATH/uperf"
    if [ ! -f "$bin" ] || [ ! -x "$bin" ] || [ -L "$bin" ]; then
        log "ERROR: uperf executable missing or unsafe: $bin"
        return 1
    fi
    local real_bin
    real_bin="$(readlink -f "$bin" 2>/dev/null)"
    [ -n "$real_bin" ] || { log "ERROR: cannot canonicalize uperf path"; return 1; }

    if ! validate_active_config; then
        log "ERROR: active uperf.json failed schema/trust-boundary validation; refusing to start."
        return 1
    fi

    [ -L "$USER_PATH/cur_powermode.txt" ] && return 1
    [ -f "$USER_PATH/cur_powermode.txt" ] || printf 'balance\n' >"$USER_PATH/cur_powermode.txt"
    ensure_safe_file "$USER_PATH/cur_powermode.txt" || return 1

    [ -L "$USER_PATH/perapp_powermode.txt" ] && return 1
    [ -f "$USER_PATH/perapp_powermode.txt" ] || printf '# default\n- powersave\n* balance\n' >"$USER_PATH/perapp_powermode.txt"
    ensure_safe_file "$USER_PATH/perapp_powermode.txt" || return 1

    [ -L "$USER_PATH/uperf_log.txt" ] && rm -f "$USER_PATH/uperf_log.txt"
    [ -f "$USER_PATH/uperf_log.txt" ] && mv -f "$USER_PATH/uperf_log.txt" "$USER_PATH/uperf_log.txt.bak"

    # Preserve original sysctl limits before adjusting inotify
    backup_inotify_limits || { log "ERROR: failed backing up inotify state"; return 1; }
    lock_val "1048576" /proc/sys/fs/inotify/max_queued_events
    lock_val "1048576" /proc/sys/fs/inotify/max_user_watches
    lock_val "1024" /proc/sys/fs/inotify/max_user_instances

    # Record pre-launch PIDs so readiness verifies a live instance backed by real_bin and fresh log output
    local prior_pids=" $(pidof uperf 2>/dev/null) "
    "$bin" "$USER_PATH/uperf.json" -o "$USER_PATH/uperf_log.txt"
    local rc=$?
    if [ "$rc" -ne 0 ]; then
        log "ERROR: uperf launcher exited with status $rc"
        return "$rc"
    fi

    sleep 2
    if [ ! -f "$USER_PATH/uperf_log.txt" ] || [ -L "$USER_PATH/uperf_log.txt" ]; then
        log "ERROR: uperf did not produce a valid runtime log file"
        return 1
    fi
    chown 0:0 "$USER_PATH/uperf_log.txt" && chmod 0600 "$USER_PATH/uperf_log.txt" || return 1

    local verified_pid=""
    for p in $(pidof uperf 2>/dev/null); do
        if [ "$(readlink -f "/proc/$p/exe" 2>/dev/null)" = "$real_bin" ]; then
            case "$prior_pids" in
                *" $p "*) ;;
                *) verified_pid="$p"; break ;;
            esac
        fi
    done
    if [ -z "$verified_pid" ]; then
        log "ERROR: newly launched uperf daemon not detected for $real_bin"
        return 1
    fi

    # Uperf shouldn't preempt foreground tasks
    rebuild_process_scan_cache
    change_task_cgroup "uperf" "background" "cpuset"
    return 0
}
