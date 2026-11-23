#!/system/bin/sh
# Hardened service launcher for FAS-RS (SCENE4R Edition, GNU GPL v3.0)

MODDIR="$(dirname "$(readlink -f "$0")")"
RUNTIME_DIR="/data/adb/fas-rs"
NODE_DIR="$RUNTIME_DIR/node"
MASK_DIR="$RUNTIME_DIR/.mount_masks"
USER_CFG="$RUNTIME_DIR/games.toml"
LOG_FILE="$RUNTIME_DIR/fas_log.txt"
BIN="$MODDIR/bin/fas-rs"
STD_CFG="$MODDIR/games.toml"

umask 077

ensure_dir() {
    local d="$1"
    [ -L "$d" ] && return 1
    mkdir -p "$d" || return 1
    [ -d "$d" ] && [ ! -L "$d" ] || return 1
    chown 0:0 "$d" && chmod 0700 "$d"
}

[ -L "/data/adb" ] && exit 1
ensure_dir "$RUNTIME_DIR" || exit 1
ensure_dir "$NODE_DIR" || exit 1
ensure_dir "$MASK_DIR" || exit 1

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

# Strict mutual exclusion: never run FAS-RS alongside enabled or active Uperf-R
if [ -f "/data/adb/modules/uperf/module.prop" ] && [ ! -f "/data/adb/modules/uperf/disable" ] && [ ! -f "/data/adb/modules/uperf/remove" ]; then
    [ -L "$LOG_FILE" ] && rm -f "$LOG_FILE"
    printf '[%s] Mutual exclusion: Uperf-R module is enabled; refusing to start FAS-RS simultaneously.\n' "$(date '+%Y-%m-%d %H:%M:%S')" >"$LOG_FILE"
    chown 0:0 "$LOG_FILE" && chmod 0600 "$LOG_FILE"
    exit 0
fi

for p in $(pidof uperf 2>/dev/null || pgrep -x uperf 2>/dev/null); do
    if [ "$(readlink -f "/proc/$p/exe" 2>/dev/null)" = "$(readlink -f /data/adb/modules/uperf/bin/uperf 2>/dev/null)" ]; then
        [ -L "$LOG_FILE" ] && rm -f "$LOG_FILE"
        printf '[%s] Mutual exclusion: uperf daemon pid=%s is still alive; refusing to start FAS-RS.\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$p" >"$LOG_FILE"
        chown 0:0 "$LOG_FILE" && chmod 0600 "$LOG_FILE"
        exit 1
    fi
done

if [ ! -f "$BIN" ] || [ ! -x "$BIN" ] || [ -L "$BIN" ]; then
    exit 1
fi
REAL_BIN="$(readlink -f "$BIN" 2>/dev/null)"
[ -n "$REAL_BIN" ] || exit 1

[ -L "$USER_CFG" ] && exit 1
if [ ! -f "$USER_CFG" ]; then
    cp -f "$STD_CFG" "$USER_CFG" || exit 1
fi
chown 0:0 "$USER_CFG" && chmod 0600 "$USER_CFG" || exit 1

if [ -f "$MODDIR/.need_merge" ] && [ ! -L "$MODDIR/.need_merge" ]; then
    TMP_MERGE="$RUNTIME_DIR/.games_merge.$$"
    rm -f "$TMP_MERGE"
    if "$BIN" merge "$STD_CFG" >"$TMP_MERGE" 2>/dev/null && [ -s "$TMP_MERGE" ] && [ ! -L "$TMP_MERGE" ]; then
        chown 0:0 "$TMP_MERGE" && chmod 0600 "$TMP_MERGE"
        if "$BIN" validate "$TMP_MERGE" >/dev/null 2>&1; then
            mv -f "$TMP_MERGE" "$USER_CFG"
            chown 0:0 "$USER_CFG" && chmod 0600 "$USER_CFG"
        fi
    fi
    rm -f "$TMP_MERGE" "$MODDIR/.need_merge"
fi

if ! "$BIN" validate "$USER_CFG" >/dev/null 2>&1; then
    cp -f "$STD_CFG" "$USER_CFG" && chown 0:0 "$USER_CFG" && chmod 0600 "$USER_CFG" || exit 1
fi

# Stop any stale verified fas-rs processes cleanly (including superseded deleted inodes after upgrade)
for p in $(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
    exe="$(readlink -f "/proc/$p/exe" 2>/dev/null)"
    if [ "$exe" = "$REAL_BIN" ] || [ "$exe" = "$REAL_BIN (deleted)" ]; then
        kill -TERM "$p" 2>/dev/null
    fi
done

wait_i=0
while [ "$wait_i" -lt 10 ]; do
    alive=0
    for p in $(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
        exe="$(readlink -f "/proc/$p/exe" 2>/dev/null)"
        if [ "$exe" = "$REAL_BIN" ] || [ "$exe" = "$REAL_BIN (deleted)" ]; then
            alive=1
            break
        fi
    done
    [ "$alive" -eq 0 ] && break
    sleep 0.2 2>/dev/null || sleep 1
    wait_i=$((wait_i + 1))
done

for p in $(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
    exe="$(readlink -f "/proc/$p/exe" 2>/dev/null)"
    if [ "$exe" = "$REAL_BIN" ] || [ "$exe" = "$REAL_BIN (deleted)" ]; then
        kill -KILL "$p" 2>/dev/null
    fi
done

[ -L "$LOG_FILE" ] && rm -f "$LOG_FILE"
if [ -f "$LOG_FILE" ]; then
    [ -L "${LOG_FILE}.bak" ] && rm -f "${LOG_FILE}.bak"
    mv -f "$LOG_FILE" "${LOG_FILE}.bak"
fi

nohup "$BIN" run "$STD_CFG" </dev/null >"$LOG_FILE" 2>&1 &

tries=0
verified_pid=""
while [ "$tries" -lt 20 ]; do
    sleep 0.2 2>/dev/null || sleep 1
    for p in $(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
        if [ "$(readlink -f "/proc/$p/exe" 2>/dev/null)" = "$REAL_BIN" ]; then
            verified_pid="$p"
            break
        fi
    done
    if [ -n "$verified_pid" ] && [ -f "$NODE_DIR/mode" ] && [ ! -L "$NODE_DIR/mode" ]; then
        break
    fi
    tries=$((tries + 1))
done

if [ -f "$LOG_FILE" ] && [ ! -L "$LOG_FILE" ]; then
    chown 0:0 "$LOG_FILE" && chmod 0600 "$LOG_FILE"
fi

[ -n "$verified_pid" ] && [ -f "$NODE_DIR/mode" ]
