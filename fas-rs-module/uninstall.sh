#!/system/bin/sh
# Hardened uninstall & state cleanup script for FAS-RS (SCENE4R Edition, GNU GPL v3.0)

MODDIR="$(dirname "$(readlink -f "$0")")"
BIN="$MODDIR/bin/fas-rs"
REAL_BIN="$(readlink -f "$BIN" 2>/dev/null)"

for p in $(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
    exe="$(readlink -f "/proc/$p/exe" 2>/dev/null)"
    if [ -n "$REAL_BIN" ] && [ "$exe" = "$REAL_BIN" ]; then
        kill -TERM "$p" 2>/dev/null
    elif [ -z "$REAL_BIN" ] && [ "${exe##*/}" = "fas-rs" ]; then
        kill -TERM "$p" 2>/dev/null
    fi
done

sleep 1
for p in $(pidof fas-rs 2>/dev/null || pgrep -x fas-rs 2>/dev/null); do
    kill -KILL "$p" 2>/dev/null
done

for target in \
    /sys/module/mtk_fpsgo/parameters/perfmgr_enable \
    /sys/module/perfmgr/parameters/perfmgr_enable \
    /sys/module/perfmgr_policy/parameters/perfmgr_enable \
    /sys/module/perfmgr_mtk/parameters/perfmgr_enable \
    /sys/module/migt/parameters/glk_fbreak_enable \
    /sys/module/migt/parameters/glk_disable \
    /proc/game_opt/disable_cpufreq_limit; do
    if grep -q " $target " /proc/mounts 2>/dev/null; then
        umount -l "$target" 2>/dev/null || umount "$target" 2>/dev/null
    fi
done

for policy in /sys/devices/system/cpu/cpufreq/policy*; do
    [ -d "$policy" ] || continue
    if [ -r "$policy/cpuinfo_min_freq" ] && [ -e "$policy/scaling_min_freq" ]; then
        chmod 0644 "$policy/scaling_min_freq" 2>/dev/null
        cat "$policy/cpuinfo_min_freq" >"$policy/scaling_min_freq" 2>/dev/null
    fi
    if [ -r "$policy/cpuinfo_max_freq" ] && [ -e "$policy/scaling_max_freq" ]; then
        chmod 0644 "$policy/scaling_max_freq" 2>/dev/null
        cat "$policy/cpuinfo_max_freq" >"$policy/scaling_max_freq" 2>/dev/null
    fi
done

if [ ! -L "/data/adb" ] && [ ! -L "/data/adb/fas-rs" ]; then
    rm -rf /data/adb/fas-rs
fi
