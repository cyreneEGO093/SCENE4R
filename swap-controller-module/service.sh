#!/system/bin/sh
MODDIR=${0%/*}

wait_count=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$wait_count" -lt 30 ]; do
    sleep 2
    wait_count=$((wait_count + 1))
done

module_version=$(grep '^versionCode=' "$MODDIR/module.prop" 2>/dev/null | head -n 1 | cut -d'=' -f2 | tr -d '\r ')
if [ -n "$module_version" ]; then
    if command -v resetprop >/dev/null 2>&1; then
        resetprop vtools.swap.module "$module_version"
    else
        setprop vtools.swap.module "$module_version"
    fi
fi

LOG_DIR="/data/adb/scene_swap_controller"
mkdir -p "$LOG_DIR"
chown 0:0 "$LOG_DIR" 2>/dev/null
chmod 0700 "$LOG_DIR" 2>/dev/null

MODDIR="$MODDIR" /system/bin/sh "$MODDIR/system/bin/scene_swap_module.sh" > "$LOG_DIR/boot.log" 2>&1
MODDIR="$MODDIR" /system/bin/sh "$MODDIR/system/bin/scene_swap_extra.sh" > "$LOG_DIR/extra.log" 2>&1
chmod 0600 "$LOG_DIR"/*.log 2>/dev/null || true

sm fstrim >/dev/null 2>&1 || true
