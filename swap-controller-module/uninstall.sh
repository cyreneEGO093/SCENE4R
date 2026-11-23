#!/system/bin/sh
# 停止运行中的 scene_swap_service.dex 后台守护进程
for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    [ -r "$p/cmdline" ] || continue
    cmd=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null)
    case "$cmd" in
        *scene_swap_service.dex*)
            kill "$pid" 2>/dev/null
            sleep 0.2
            kill -9 "$pid" 2>/dev/null
            ;;
    esac
done

rm -rf /data/swapfile*
rm -f /data/swap_recreate
rm -f /data/swap_config.conf
rm -rf /data/adb/scene_swap_controller
