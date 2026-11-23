#!/system/bin/sh
#
# Hardened config importer for Uperf-R (SCENE4R Edition)
# - Exclusive lock registered strictly AFTER acquisition
# - Requires exclusive mktemp staging under /data/adb/uperf (0700)
# - Bounded single-pass read (<= 512 KiB)
# - Emits decoded knob targets directly from strict JSON parser (validate_config.awk)
#

BASEDIR="$(dirname "$(readlink -f "$0")")"
. "$BASEDIR/pathinfo.sh"

umask 077

src="$1"
[ -n "$src" ] || { echo "Usage: $0 <path-to-uperf.json>" >&2; exit 1; }
[ -f "$src" ] && [ ! -L "$src" ] || { echo "Rejected: missing file or symlink: $src" >&2; exit 1; }

[ -L "/data/adb" ] && { echo "Rejected: /data/adb is a symlink" >&2; exit 1; }
[ -L "$USER_PATH" ] && { echo "Rejected: $USER_PATH is a symlink" >&2; exit 1; }
mkdir -p "$USER_PATH" || exit 1
[ -d "$USER_PATH" ] && [ ! -L "$USER_PATH" ] || { echo "Rejected: $USER_PATH invalid directory" >&2; exit 1; }
chown 0:0 "$USER_PATH" && chmod 0700 "$USER_PATH" || exit 1

bb="$BIN_PATH/busybox/busybox"
[ -x "$bb" ] && [ ! -L "$bb" ] || { echo "Rejected: pinned busybox unavailable" >&2; exit 1; }

lock_dir="$USER_PATH/.import.lock"
if ! mkdir "$lock_dir" 2>/dev/null; then
    echo "Rejected: another config write/import operation is in progress" >&2
    exit 1
fi

tmp_cfg=""
knobs_file=""
cleanup() {
    [ -n "$tmp_cfg" ] && rm -f "$tmp_cfg"
    [ -n "$knobs_file" ] && rm -f "$knobs_file"
    rmdir "$lock_dir" 2>/dev/null || true
}
# Release lock on EXIT only; signals terminate cleanly so cleanup runs via EXIT
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

tmp_cfg="$("$bb" mktemp "$USER_PATH/.import_tmp.XXXXXX" 2>/dev/null)"
knobs_file="$("$bb" mktemp "$USER_PATH/.import_knobs.XXXXXX" 2>/dev/null)"
if [ -z "$tmp_cfg" ] || [ -z "$knobs_file" ] || [ ! -f "$tmp_cfg" ] || [ -L "$tmp_cfg" ] || [ ! -f "$knobs_file" ] || [ -L "$knobs_file" ]; then
    echo "Rejected: unable to allocate exclusive staging files" >&2
    exit 1
fi
chown 0:0 "$tmp_cfg" "$knobs_file" && chmod 0600 "$tmp_cfg" "$knobs_file" || exit 1

# Bounded single-pass read (max 512 KiB + 1 byte sentinel)
dd if="$src" of="$tmp_cfg" bs=1024 count=513 conv=notrunc 2>/dev/null || {
    echo "Rejected: failed reading source config" >&2
    exit 1
}
chown 0:0 "$tmp_cfg" && chmod 0600 "$tmp_cfg" || exit 1

size="$(wc -c <"$tmp_cfg" 2>/dev/null)"
case "$size" in
    '' | *[!0-9]*)
        echo "Rejected: invalid config size" >&2
        exit 1
        ;;
esac
if [ "$size" -le 0 ] || [ "$size" -gt 524288 ]; then
    echo "Rejected: invalid config size ($size bytes)" >&2
    exit 1
fi

# Strict JSON + duplicate-key + allowlist validation, emitting decoded knob targets
if ! "$bb" awk -v KNOBS_OUT="$knobs_file" -f "$BASEDIR/validate_config.awk" "$tmp_cfg"; then
    echo "Rejected: config failed schema/allowlist validation" >&2
    exit 1
fi

# Re-check canonical target safety on decoded knob targets
while IFS= read -r p; do
    [ -n "$p" ] || continue
    if [ -L "$p" ]; then
        echo "Rejected: device knob path is a symlink: $p" >&2
        exit 1
    fi
    if [ -e "$p" ]; then
        real_p="$(readlink -f "$p" 2>/dev/null)" || { echo "Rejected: readlink failed for $p" >&2; exit 1; }
        case "$real_p" in
        /sys/* | /dev/cpuset/* | /proc/cpufreq/*) ;;
        *)
            echo "Rejected: resolved path escapes allowed tree: $p -> $real_p" >&2
            exit 1
            ;;
        esac
    fi
done <"$knobs_file"

[ -L "$USER_PATH/uperf.json" ] && {
    echo "Rejected: destination uperf.json is a symlink" >&2
    exit 1
}
if [ -f "$USER_PATH/uperf.json" ]; then
    [ -L "$USER_PATH/uperf.json.bak" ] && { echo "Rejected: uperf.json.bak is a symlink" >&2; exit 1; }
    cp -f "$USER_PATH/uperf.json" "$USER_PATH/uperf.json.bak" || exit 1
    chown 0:0 "$USER_PATH/uperf.json.bak" && chmod 0600 "$USER_PATH/uperf.json.bak" || exit 1
fi

mv -f "$tmp_cfg" "$USER_PATH/uperf.json" || {
    echo "Failed to publish validated config" >&2
    exit 1
}
tmp_cfg=""
chown 0:0 "$USER_PATH/uperf.json" && chmod 0600 "$USER_PATH/uperf.json" || exit 1
echo "Imported validated config to $USER_PATH/uperf.json"
