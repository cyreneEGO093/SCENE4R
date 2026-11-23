#!/vendor/bin/sh
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
. "$BASEDIR/libuperf.sh"

umask 077
JOURNAL="$BACKUP_PATH/journal.tsv"

# Ensure safe runtime & backup directories BEFORE creating bridge files
[ -L "/data/adb" ] && exit 1
[ -L "$USER_PATH" ] && exit 1
mkdir -p "$USER_PATH" || exit 1
[ -d "$USER_PATH" ] && [ ! -L "$USER_PATH" ] || exit 1
chown 0:0 "$USER_PATH" && chmod 0700 "$USER_PATH" || exit 1

[ -L "$BACKUP_PATH" ] && exit 1
mkdir -p "$BACKUP_PATH" || exit 1
[ -d "$BACKUP_PATH" ] && [ ! -L "$BACKUP_PATH" ] || exit 1
chown 0:0 "$BACKUP_PATH" && chmod 0700 "$BACKUP_PATH" || exit 1

[ -L "$JOURNAL" ] && exit 1

# Fail closed BEFORE touching /data/powercfg* or running powercfg_once.sh on non-4KiB/unknown page size
if ! check_page_size_compat; then
    log "ERROR: Aborting initialization before bridge writes due to unsupported or unknown kernel PAGESIZE."
    exit 1
fi

# Create private busybox symlinks safely across first boot and subsequent reboots
if [ ! -L "$BIN_PATH/busybox/awk" ]; then
    "$BIN_PATH/busybox/busybox" --install -s "$BIN_PATH/busybox" 2>/dev/null || true
fi
[ -x "$BIN_PATH/busybox/awk" ] && [ -x "$BIN_PATH/busybox/mktemp" ] || exit 1

journal_upsert() {
    local t="$1" ho="$2" u="$3" g="$4" m="$5" osha="$6" nsha="$7" st="$8"
    local tmp_j
    tmp_j="$("$BIN_PATH/busybox/busybox" mktemp "$BACKUP_PATH/.journal_tmp.XXXXXX" 2>/dev/null)" || return 1
    if [ -f "$JOURNAL" ]; then
        grep -v "^${t}	" "$JOURNAL" >"$tmp_j" 2>/dev/null || true
    else
        : >"$tmp_j"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$t" "$ho" "$u" "$g" "$m" "$osha" "$nsha" "$st" >>"$tmp_j" || { rm -f "$tmp_j"; return 1; }
    chown 0:0 "$tmp_j" && chmod 0600 "$tmp_j" && mv -f "$tmp_j" "$JOURNAL" || { rm -f "$tmp_j"; return 1; }
}

install_managed_file() {
    local target="$1"
    local bname="$2"
    local src="$3"
    local mode="$4"
    local append_entry="$5"

    [ -L "$target" ] && { log "ERROR: symlink at $target"; return 1; }

    local had_orig=0 uid=0 gid=0 orig_mode="${mode#0}" orig_sha="-"
    local bak="$BACKUP_PATH/$bname"
    [ -L "$bak" ] && { log "ERROR: symlink at backup $bak"; return 1; }

    local bb="$BIN_PATH/busybox/busybox"
    local tmp_target
    tmp_target="$("$bb" mktemp "/data/.uperf_${bname}.XXXXXX" 2>/dev/null)" || return 1
    cp -f "$src" "$tmp_target" || { rm -f "$tmp_target"; return 1; }
    if [ -n "$append_entry" ]; then
        printf '%s\n' "$append_entry" >>"$tmp_target" || { rm -f "$tmp_target"; return 1; }
    fi
    chown 0:0 "$tmp_target" && chmod "$mode" "$tmp_target" || { rm -f "$tmp_target"; return 1; }
    local planned_sha
    planned_sha="$(sha256sum "$tmp_target" 2>/dev/null | awk '{print $1}')"
    [ -n "$planned_sha" ] || { rm -f "$tmp_target"; return 1; }

    # Determine ownership strictly from verified journal digest rather than marker text alone
    if [ -f "$target" ]; then
        local is_ours=0
        if [ -f "$JOURNAL" ] && grep -q "^${target}	" "$JOURNAL" 2>/dev/null; then
            local rec_row rec_sha cur_sha
            rec_row="$(grep "^${target}	" "$JOURNAL" | tail -n 1)"
            rec_sha="$(printf '%s\n' "$rec_row" | cut -f7)"
            cur_sha="$(sha256sum "$target" 2>/dev/null | awk '{print $1}')"
            [ -n "$rec_sha" ] && [ "$rec_sha" = "$cur_sha" ] && is_ours=1
        fi

        if [ "$is_ours" -eq 0 ]; then
            had_orig=1
            uid="$(stat -c '%u' "$target" 2>/dev/null)" || { rm -f "$tmp_target"; return 1; }
            gid="$(stat -c '%g' "$target" 2>/dev/null)" || { rm -f "$tmp_target"; return 1; }
            orig_mode="$(stat -c '%a' "$target" 2>/dev/null)" || { rm -f "$tmp_target"; return 1; }
            orig_sha="$(sha256sum "$target" 2>/dev/null | awk '{print $1}')"
            [ -n "$uid" ] && [ -n "$gid" ] && [ -n "$orig_mode" ] && [ -n "$orig_sha" ] || { rm -f "$tmp_target"; return 1; }

            local bak_tmp
            bak_tmp="$("$bb" mktemp "$BACKUP_PATH/.${bname}.XXXXXX" 2>/dev/null)" || { rm -f "$tmp_target"; return 1; }
            cp -f "$target" "$bak_tmp" && chown 0:0 "$bak_tmp" && chmod 0600 "$bak_tmp" || { rm -f "$tmp_target" "$bak_tmp"; return 1; }
            local bak_sha
            bak_sha="$(sha256sum "$bak_tmp" 2>/dev/null | awk '{print $1}')"
            if [ "$bak_sha" != "$orig_sha" ]; then
                rm -f "$tmp_target" "$bak_tmp"
                return 1
            fi
            mv -f "$bak_tmp" "$bak" || { rm -f "$tmp_target" "$bak_tmp"; return 1; }
        elif [ -f "$JOURNAL" ] && grep -q "^${target}	1	" "$JOURNAL" 2>/dev/null; then
            local prev
            prev="$(grep "^${target}	" "$JOURNAL" | tail -n 1)"
            had_orig=1
            uid="$(printf '%s\n' "$prev" | cut -f3)"
            gid="$(printf '%s\n' "$prev" | cut -f4)"
            orig_mode="$(printf '%s\n' "$prev" | cut -f5)"
            orig_sha="$(printf '%s\n' "$prev" | cut -f6)"
            [ -n "$uid" ] && [ -n "$gid" ] && [ -n "$orig_mode" ] && [ -n "$orig_sha" ] || { rm -f "$tmp_target"; return 1; }
        fi
    fi

    # Write-ahead PENDING journal commit BEFORE replacing live target
    journal_upsert "$target" "$had_orig" "$uid" "$gid" "$orig_mode" "$orig_sha" "$planned_sha" "PENDING" || { rm -f "$tmp_target"; return 1; }

    mv -f "$tmp_target" "$target" || { rm -f "$tmp_target"; return 1; }
    local live_sha live_meta expected_norm_mode
    expected_norm_mode="${mode#0}"
    live_sha="$(sha256sum "$target" 2>/dev/null | awk '{print $1}')"
    live_meta="$(stat -c '%u:%g:%a' "$target" 2>/dev/null)"
    if [ "$live_sha" != "$planned_sha" ] || [ "$live_meta" != "0:0:${expected_norm_mode}" ]; then
        log "ERROR: post-install verification failed for $target"
        return 1
    fi

    journal_upsert "$target" "$had_orig" "$uid" "$gid" "$orig_mode" "$orig_sha" "$planned_sha" "COMMITTED" || return 1
}

install_managed_file "/data/powercfg.sh" "powercfg.sh" "$SCRIPT_PATH/vtools_powercfg.sh" "0755" "sh \"$SCRIPT_PATH/powercfg_main.sh\" \"\$1\"" || exit 1
install_managed_file "/data/powercfg-base.sh" "powercfg-base.sh" "$SCRIPT_PATH/vtools_powercfg.sh" "0755" "" || exit 1
install_managed_file "/data/powercfg.json" "powercfg.json" "$SCRIPT_PATH/powercfg.json" "0644" "" || exit 1

wait_until_boot_completed

sh "$SCRIPT_PATH/powercfg_once.sh" || {
    log "ERROR: powercfg_once.sh failed"
    exit 1
}

if uperf_start; then
    # Clear bootloop flag only after verified daemon identity & readiness
    rm -f "$FLAG_PATH/need_recuser"
else
    log "WARNING: uperf_start failed; keeping bootloop recovery flag"
    exit 1
fi
