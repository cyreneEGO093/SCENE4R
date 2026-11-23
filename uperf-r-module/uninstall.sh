#!/system/bin/sh
#
# Hardened transactional rollback & uninstall script for Uperf-R (SCENE4R Edition)
# - Verifies non-symlink /data/adb, runtime, backup, and journal paths
# - Checks live target digest against BOTH orig_sha and planned/installed_sha (even on PENDING)
# - Verifies original backup SHA-256 and restored bytes + uid/gid/mode before discarding backups
# - Verifies inotify restore values/ownership/mode
# - Supports `--rollback-bridge-only` without wiping user uperf.json
#

BRIDGE_ONLY=0
[ "$1" = "--rollback-bridge-only" ] && BRIDGE_ONLY=1

USER_PATH="/data/adb/uperf"
BACKUP_PATH="$USER_PATH/backup"
JOURNAL="$BACKUP_PATH/journal.tsv"
UNRESOLVED_JOURNAL="$BACKUP_PATH/journal.unresolved.tsv"
INOTIFY_BAK="$BACKUP_PATH/inotify.bak"

killall uperf 2>/dev/null

if [ -L "/data/adb" ] || [ -L "$USER_PATH" ] || [ -L "$BACKUP_PATH" ]; then
    exit 1
fi

restore_err=0

restore_inotify() {
    if [ -f "$INOTIFY_BAK" ] && [ ! -L "$INOTIFY_BAK" ]; then
        local inotify_err=0
        while IFS='	' read -r ipath ival iuid igid imode; do
            [ -n "$ipath" ] || continue
            if [ ! -f "$ipath" ] || [ -L "$ipath" ]; then
                inotify_err=1
                continue
            fi
            chmod 0644 "$ipath" 2>/dev/null
            if printf '%s\n' "$ival" >"$ipath" 2>/dev/null && chown "$iuid:$igid" "$ipath" 2>/dev/null && chmod "$imode" "$ipath" 2>/dev/null; then
                local cur_val cur_meta
                cur_val="$(cat "$ipath" 2>/dev/null)"
                cur_meta="$(stat -c '%u:%g:%a' "$ipath" 2>/dev/null)"
                if [ "$cur_val" != "$ival" ] || [ "$cur_meta" != "${iuid}:${igid}:${imode#0}" ]; then
                    inotify_err=1
                fi
            else
                inotify_err=1
            fi
        done <"$INOTIFY_BAK"
        if [ "$inotify_err" -eq 0 ]; then
            rm -f "$INOTIFY_BAK"
        else
            restore_err=1
        fi
    fi
}

if [ ! -e "$JOURNAL" ]; then
    restore_inotify
    if [ -d "$BACKUP_PATH" ] && [ -n "$(ls -A "$BACKUP_PATH" 2>/dev/null)" ]; then
        exit 1
    fi
    [ "$BRIDGE_ONLY" -eq 0 ] && [ "$restore_err" -eq 0 ] && rm -rf "$USER_PATH"
    exit "$restore_err"
fi

if [ -L "$JOURNAL" ] || [ ! -f "$JOURNAL" ]; then
    exit 1
fi

unres_tmp="${UNRESOLVED_JOURNAL}.tmp.$$"
: >"$unres_tmp" 2>/dev/null && chmod 0600 "$unres_tmp" 2>/dev/null

while IFS='	' read -r target had_orig uid gid orig_mode orig_sha installed_sha state; do
    [ -n "$target" ] || continue
    case "$target" in
    /data/powercfg.sh | /data/powercfg-base.sh | /data/powercfg.json) ;;
    *) continue ;;
    esac

    raw_row="${target}	${had_orig}	${uid}	${gid}	${orig_mode}	${orig_sha}	${installed_sha}	${state}"

    bname="$(basename "$target")"
    bak="$BACKUP_PATH/$bname"

    if [ -L "$target" ]; then
        restore_err=1
        printf '%s\n' "$raw_row" >>"$unres_tmp"
        continue
    elif [ -e "$target" ] && [ ! -f "$target" ]; then
        # Unexpected non-regular object (directory/FIFO/device); preserve backup unresolved
        restore_err=1
        printf '%s\n' "$raw_row" >>"$unres_tmp"
        continue
    elif [ -f "$target" ]; then
        cur_sha="$(sha256sum "$target" 2>/dev/null | awk '{print $1}')"
        [ -n "$cur_sha" ] || { restore_err=1; printf '%s\n' "$raw_row" >>"$unres_tmp"; continue; }

        if [ "$had_orig" = "1" ] && [ "$cur_sha" = "$orig_sha" ]; then
            cur_meta="$(stat -c '%u:%g:%a' "$target" 2>/dev/null)"
            if [ "$cur_meta" = "${uid}:${gid}:${orig_mode#0}" ]; then
                rm -f "$bak"
                continue
            fi
        fi

        expected_mode="644"
        [ "$bname" = "powercfg.sh" ] || [ "$bname" = "powercfg-base.sh" ] && expected_mode="755"
        cur_meta="$(stat -c '%u:%g:%a' "$target" 2>/dev/null)"
        if [ "$cur_sha" != "$installed_sha" ] || [ "$cur_meta" != "0:0:${expected_mode}" ]; then
            restore_err=1
            printf '%s\n' "$raw_row" >>"$unres_tmp"
            continue
        fi
    fi

    if [ "$had_orig" = "1" ]; then
        if [ ! -f "$bak" ] || [ -L "$bak" ]; then
            restore_err=1
            printf '%s\n' "$raw_row" >>"$unres_tmp"
            continue
        fi
        bak_sha="$(sha256sum "$bak" 2>/dev/null | awk '{print $1}')"
        if [ -z "$bak_sha" ] || [ "$bak_sha" != "$orig_sha" ]; then
            restore_err=1
            printf '%s\n' "$raw_row" >>"$unres_tmp"
            continue
        fi

        tmp_restore="${target}.uperf_restore.$$"
        if cp -f "$bak" "$tmp_restore" && chown "$uid:$gid" "$tmp_restore" && chmod "$orig_mode" "$tmp_restore" && mv -f "$tmp_restore" "$target"; then
            res_sha="$(sha256sum "$target" 2>/dev/null | awk '{print $1}')"
            res_meta="$(stat -c '%u:%g:%a' "$target" 2>/dev/null)"
            if [ "$res_sha" = "$orig_sha" ] && [ "$res_meta" = "${uid}:${gid}:${orig_mode#0}" ]; then
                rm -f "$bak"
            else
                restore_err=1
                printf '%s\n' "$raw_row" >>"$unres_tmp"
            fi
        else
            rm -f "$tmp_restore"
            restore_err=1
            printf '%s\n' "$raw_row" >>"$unres_tmp"
        fi
    else
        if [ -e "$target" ]; then
            rm -f "$target" || { restore_err=1; printf '%s\n' "$raw_row" >>"$unres_tmp"; }
        fi
    fi
done <"$JOURNAL"

restore_inotify

if [ -s "$unres_tmp" ]; then
    mv -f "$unres_tmp" "$UNRESOLVED_JOURNAL"
    chown 0:0 "$UNRESOLVED_JOURNAL" 2>/dev/null
    chmod 0600 "$UNRESOLVED_JOURNAL" 2>/dev/null
    restore_err=1
else
    rm -f "$unres_tmp"
fi

if [ "$BRIDGE_ONLY" -eq 0 ]; then
    if [ "$restore_err" -eq 0 ]; then
        rm -rf "$USER_PATH"
    else
        rm -f "$USER_PATH/cur_powermode.txt" "$USER_PATH/uperf.json" "$USER_PATH/uperf.json.bak" "$USER_PATH/uperf.default.json" "$USER_PATH/uperf_log.txt" "$USER_PATH/uperf_log.txt.bak" "$USER_PATH/initsvc.log"
    fi
fi

exit "$restore_err"
