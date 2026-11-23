#!/system/bin/sh
# SCENE4R SWAP 控制器后台回收守护启动脚本 (GPL-3.0, 适配 Android 16 cgroup v2)

swap_config="/data/swap_config.conf"

if [ -z "$MODDIR" ]; then
    case "$0" in
        /data/adb/modules/*) MODDIR=${0%/system/bin/*} ;;
        *) MODDIR="/data/adb/modules/scene_swap_controller" ;;
    esac
fi

DEX_PATH="$MODDIR/system/bin/scene_swap_service.dex"
[ -f "$DEX_PATH" ] || DEX_PATH="/system/bin/scene_swap_service.dex"

get_prop() {
    [ -r "$swap_config" ] || return 0
    grep "^$1=" "$swap_config" 2>/dev/null | head -n 1 | cut -d'=' -f2- | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

set_top_app() {
    target="$1"
    for pid in $(pgrep -f "$target" 2>/dev/null); do
        if [ -w /dev/cpuset/top-app/cgroup.procs ]; then
            echo "$pid" > /dev/cpuset/top-app/cgroup.procs 2>/dev/null
        fi
        if [ -w /dev/stune/top-app/cgroup.procs ]; then
            echo "$pid" > /dev/stune/top-app/cgroup.procs 2>/dev/null
        fi
    done
}

daemon_service=$(get_prop daemon_service)
if [ -z "$daemon_service" ] || [ "$daemon_service" = "off" ]; then
    echo "未启用 [daemon_service]"
    exit 0
fi

MemTotalKB=$(awk '/^MemTotal:/ {print $2; exit}' /proc/meminfo 2>/dev/null)
SwapTotalKB=$(awk '/^SwapTotal:/ {print $2; exit}' /proc/meminfo 2>/dev/null)
[ -n "$MemTotalKB" ] || MemTotalKB=0
[ -n "$SwapTotalKB" ] || SwapTotalKB=0

zramSizeKB=0
if [ -r /sys/block/zram0/disksize ]; then
    zramSizeBytes=$(cat /sys/block/zram0/disksize 2>/dev/null)
    case "$zramSizeBytes" in
        ''|*[!0-9]*) zramSizeKB=0 ;;
        *) zramSizeKB=$((zramSizeBytes / 1024)) ;;
    esac
fi

echo "配置关键渲染线程 cpuset"
set_top_app vendor.qti.hardware.display.composer-service
set_top_app android.hardware.graphics.composer
set_top_app surfaceflinger
set_top_app system_server

if [ "$daemon_service" != "basic" ]; then
    if [ "$MemTotalKB" -le 4194304 ]; then
        echo "  设备内存 <= 4GB，回退为 [basic]"
        daemon_service="basic"
    elif [ "$SwapTotalKB" -le 1572864 ]; then
        echo "  SWAP <= 1.5GB，回退为 [basic]"
        daemon_service="basic"
    elif [ -r /sys/block/zram0/backing_dev ]; then
        backing_dev=$(cat /sys/block/zram0/backing_dev 2>/dev/null)
        if [ -z "$backing_dev" ] || [ "$backing_dev" = "none" ]; then
            if [ "$zramSizeKB" -gt $((MemTotalKB / 3)) ]; then
                echo "  未配置 writeback 且 ZRAM > RAM 的 1/3，调整为 [basic]"
                daemon_service="basic"
            fi
        fi
    fi
fi

# 检查内存 cgroup 模式：Android 12–16 (cgroup v2 memory.reclaim) vs 旧版 cgroup v1
if [ -f /sys/fs/cgroup/cgroup.controllers ] && grep -qw memory /sys/fs/cgroup/cgroup.controllers 2>/dev/null && [ -e /sys/fs/cgroup/memory.reclaim ]; then
    echo "检测到 cgroup v2 (memory.reclaim)"
elif [ -e /proc/1/reclaim ]; then
    scene_memcg=""
    if [ -d /sys/fs/cgroup/memory ]; then
        scene_memcg="/sys/fs/cgroup/memory"
    elif [ -d /dev/memcg ]; then
        scene_memcg="/dev/memcg"
    fi

    if [ -n "$scene_memcg" ]; then
        echo "检测到旧版 cgroup v1 ($scene_memcg) + /proc/<pid>/reclaim"
        a_swappiness=0
        g_swappiness=20
        limit="-1"
        if [ "$daemon_service" = "basic" ]; then
            [ -r "$scene_memcg/memory.swappiness" ] && g_swappiness=$(cat "$scene_memcg/memory.swappiness" 2>/dev/null)
            a_swappiness="$g_swappiness"
        elif [ "$daemon_service" = "lazy" ]; then
            g_swappiness=20
            limit="-1"
        elif [ "$daemon_service" = "active" ]; then
            g_swappiness=20
            limit="$((MemTotalKB / 1024 / 10))M"
        elif [ "$daemon_service" = "force" ]; then
            g_swappiness=0
            limit="$((MemTotalKB / 1024 / 30))M"
        else
            g_swappiness=20
            limit="$((MemTotalKB / 1024 / 4))M"
        fi

        for grp in scene_active scene_idle; do
            mkdir -p "$scene_memcg/$grp" 2>/dev/null
            if [ "$grp" = "scene_active" ]; then
                echo "$a_swappiness" > "$scene_memcg/$grp/memory.swappiness" 2>/dev/null
            else
                echo "100" > "$scene_memcg/$grp/memory.swappiness" 2>/dev/null
            fi
            echo 1 > "$scene_memcg/$grp/memory.oom_control" 2>/dev/null
            echo 1 > "$scene_memcg/$grp/memory.use_hierarchy" 2>/dev/null
        done
        [ -w "$scene_memcg/memory.swappiness" ] && echo "$g_swappiness" > "$scene_memcg/memory.swappiness" 2>/dev/null
        [ -w "$scene_memcg/scene_idle/memory.soft_limit_in_bytes" ] && echo "$limit" > "$scene_memcg/scene_idle/memory.soft_limit_in_bytes" 2>/dev/null
    fi
else
    echo "当前内核既无 cgroup v2 memory.reclaim 也无 /proc/1/reclaim，跳过后台回收守护"
    exit 0
fi

[ -f "$DEX_PATH" ] || {
    echo "未找到 $DEX_PATH，退出"
    exit 1
}

# 启动前清理旧实例，避免重复驻留
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

LOG_DIR="/data/adb/scene_swap_controller"
mkdir -p "$LOG_DIR"
chown 0:0 "$LOG_DIR" 2>/dev/null
chmod 0700 "$LOG_DIR" 2>/dev/null

echo "启动 scene_swap_service ($daemon_service)"
nohup dalvikvm -cp "$DEX_PATH" Main "$daemon_service" > "$LOG_DIR/daemon.log" 2>&1 &
echo "全部完成!"
