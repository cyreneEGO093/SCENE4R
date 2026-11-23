#!/system/bin/sh
# SCENE4R SWAP 控制器开机配置脚本 (GPL-3.0, 适配 Android 16)

swap_config="/data/swap_config.conf"
swapdir="/data"
swapfile="${swapdir}/swapfile"
recreate="${swapdir}/swap_recreate"
loop_save="vtools.swap.loop"
next_loop_path=""

resolve_bin() {
    name="$1"
    for p in "/system/bin/$name" "/vendor/bin/$name" "/data/adb/magisk/busybox"; do
        if [ -x "$p" ]; then
            if [ "${p##*/}" = "busybox" ]; then
                echo "$p $name"
            else
                echo "$p"
            fi
            return 0
        fi
    done
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
        return 0
    fi
    return 1
}

SWAPON_BIN=$(resolve_bin swapon || echo swapon)
SWAPOFF_BIN=$(resolve_bin swapoff || echo swapoff)
MKSWAP_BIN=$(resolve_bin mkswap || echo mkswap)
DD_BIN=$(resolve_bin dd || echo dd)
LOSETUP_BIN=$(resolve_bin losetup || echo losetup)
TASKSET_BIN=$(resolve_bin taskset || echo "")
RESETPROP_BIN=$(command -v resetprop 2>/dev/null || echo "")

is_uint() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

is_int() {
    case "$1" in
        ''|'-') return 1 ;;
        -*) is_uint "${1#-}" ;;
        *) is_uint "$1" ;;
    esac
}

get_prop() {
    [ -r "$swap_config" ] || return 0
    grep "^$1=" "$swap_config" 2>/dev/null | head -n 1 | cut -d'=' -f2- | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

set_value() {
    val="$1"
    path="$2"
    if [ -e "$path" ]; then
        chmod 0644 "$path" 2>/dev/null
        printf '%s\n' "$val" > "$path" 2>/dev/null
        return $?
    fi
    return 1
}

[ -r "$swap_config" ] || {
    echo "未找到配置文件: $swap_config"
    exit 0
}

swap_enable=$(get_prop swap)
swap_size=$(get_prop swap_size)
swap_priority=$(get_prop swap_priority)
swap_use_loop=$(get_prop swap_use_loop)
zram_enable=$(get_prop zram)
zram_writeback=$(get_prop zram_writeback)
zram_size=$(get_prop zram_size)
comp_algorithm=$(get_prop comp_algorithm)
swappiness=$(get_prop swappiness)
extra_free_kbytes=$(get_prop extra_free_kbytes)
watermark_scale_factor=$(get_prop watermark_scale_factor)
kswapd_cpus=$(get_prop kswapd_cpus)

get_next_loop() {
    current_loop=$(getprop "$loop_save")
    if [ -n "$current_loop" ] && [ -e "$current_loop" ]; then
        next_loop_path="$current_loop"
        return 0
    fi
    nl=$($LOSETUP_BIN -f 2>/dev/null)
    if [ -n "$nl" ]; then
        next_loop_path="$nl"
        return 0
    fi
    next_loop_path=""
    return 1
}

set_zram_writeback() {
    bd_path="/sys/block/zram0/backing_dev"
    [ -e "$bd_path" ] || return 0

    if [ ! -f /data/writeback ]; then
        wb_size="$zram_size"
        is_uint "$wb_size" || wb_size=2048
        [ "$wb_size" -gt 0 ] || wb_size=2048
        $DD_BIN if=/dev/zero of=/data/writeback bs=1048576 count="$wb_size" >/dev/null 2>&1
    fi
    chown 0:0 /data/writeback 2>/dev/null
    chmod 0600 /data/writeback 2>/dev/null

    loop_path=$($LOSETUP_BIN -f 2>/dev/null)
    if [ -z "$loop_path" ]; then
        echo "  无法分配回环设备用于 ZRAM Writeback"
        return 1
    fi
    $LOSETUP_BIN "$loop_path" /data/writeback || return 1
    set_value "$loop_path" "$bd_path"
    set_value 0 /sys/block/zram0/writeback_limit_enable
}

set_zram() {
    echo "*************************"
    if [ "$zram_enable" != "true" ] || ! is_uint "$zram_size"; then
        echo "未启用ZRAM配置，保留系统当前ZRAM状态"
        return 0
    fi

    if [ ! -e /dev/block/zram0 ]; then
        if [ -r /sys/class/zram-control/hot_add ]; then
            cat /sys/class/zram-control/hot_add >/dev/null 2>&1
        else
            echo "内核不支持ZRAM!"
            return 1
        fi
    fi

    echo "设置ZRAM (${zram_size}MB)"
    repeat=0
    while grep -q '/zram0' /proc/swaps 2>/dev/null && [ "$repeat" -lt 25 ]; do
        $SWAPOFF_BIN /dev/block/zram0 2>/dev/null
        repeat=$((repeat + 1))
        grep -q '/zram0' /proc/swaps 2>/dev/null && sleep 1
    done

    if [ "$zram_size" -eq 0 ]; then
        set_value 1 /sys/block/zram0/reset
        echo "ZRAM大小设为0，已关闭ZRAM"
        return 0
    fi

    backing_dev=""
    bd_path="/sys/block/zram0/backing_dev"
    if [ -r "$bd_path" ]; then
        backing_dev=$(cat "$bd_path" 2>/dev/null)
    fi

    set_value 1 /sys/block/zram0/reset
    set_value 4 /sys/block/zram0/max_comp_streams

    if [ -e "$bd_path" ]; then
        if [ "$zram_writeback" = "true" ]; then
            set_zram_writeback
        elif [ "$zram_writeback" = "default" ] && [ -n "$backing_dev" ] && [ "$backing_dev" != "none" ]; then
            set_value "$backing_dev" "$bd_path"
        fi
    fi

    if [ -n "$comp_algorithm" ] && [ -r /sys/block/zram0/comp_algorithm ]; then
        if grep -qw "$comp_algorithm" /sys/block/zram0/comp_algorithm 2>/dev/null; then
            set_value "$comp_algorithm" /sys/block/zram0/comp_algorithm
        else
            echo "  压缩算法 [$comp_algorithm] 内核不支持，跳过"
        fi
    fi

    set_value 4 /sys/block/zram0/max_comp_streams
    set_value "${zram_size}M" /sys/block/zram0/disksize
    $MKSWAP_BIN /dev/block/zram0 >/dev/null 2>&1

    if [ "$swap_priority" = "0" ]; then
        $SWAPON_BIN /dev/block/zram0 -p 0 >/dev/null 2>&1
    else
        $SWAPON_BIN /dev/block/zram0 >/dev/null 2>&1
    fi
}

set_swap() {
    echo "*************************"
    if [ -f "$recreate" ]; then
        rm -rf "${swapfile}"* "$recreate"
        echo "已删除已有swapfile等待重建"
    fi

    if [ "$swap_enable" != "true" ]; then
        echo "未启用Swapfile"
        return 0
    fi

    if [ ! -f "$swapfile" ]; then
        if ! is_uint "$swap_size" || [ "$swap_size" -le 0 ]; then
            echo "Swapfile大小配置无效: [$swap_size]，跳过"
            return 1
        fi
        echo "创建Swapfile (${swap_size}MB)"
        $DD_BIN if=/dev/zero of="$swapfile" bs=1048576 count="$swap_size" >/dev/null 2>&1 || return 1
    fi
    chown 0:0 "$swapfile" 2>/dev/null
    chmod 0600 "$swapfile" 2>/dev/null

    set_value true /sys/kernel/mm/swap/vma_ra_enabled >/dev/null 2>&1 || true

    swap_mount="$swapfile"
    if [ "$swap_use_loop" = "true" ]; then
        get_next_loop || {
            echo "无法分配回环设备，Swapfile挂载失败" >&2
            return 1
        }
        swap_mount="$next_loop_path"
        $LOSETUP_BIN -d "$swap_mount" 2>/dev/null || true
        $MKSWAP_BIN "$swapfile" >/dev/null 2>&1
        $LOSETUP_BIN "$swap_mount" "$swapfile" || return 1
        setprop "$loop_save" "$next_loop_path"
    else
        $MKSWAP_BIN "$swap_mount" >/dev/null 2>&1
    fi

    echo "启用Swapfile"
    if is_int "$swap_priority" && [ "$swap_priority" -gt -1 ]; then
        $SWAPON_BIN "$swap_mount" -p "$swap_priority" >/dev/null 2>&1
    else
        $SWAPON_BIN "$swap_mount" >/dev/null 2>&1
    fi
}

set_vm_params() {
    echo "*************************"
    if is_uint "$watermark_scale_factor" && [ "$watermark_scale_factor" -ge 1 ] && [ "$watermark_scale_factor" -le 1000 ]; then
        echo "设置 watermark_scale_factor [$watermark_scale_factor]"
        set_value "$watermark_scale_factor" /proc/sys/vm/watermark_scale_factor
    fi

    if is_uint "$extra_free_kbytes" && [ "$extra_free_kbytes" -le 2097152 ]; then
        if [ -w /proc/sys/vm/extra_free_kbytes ]; then
            echo "设置 extra_free_kbytes [$extra_free_kbytes]"
            set_value "$extra_free_kbytes" /proc/sys/vm/extra_free_kbytes
            [ -n "$RESETPROP_BIN" ] && $RESETPROP_BIN sys.sysctl.extra_free_kbytes "$extra_free_kbytes"
        else
            echo "内核不支持 extra_free_kbytes，跳过"
        fi
    fi

    if is_uint "$swappiness" && [ "$swappiness" -le 200 ]; then
        echo "设置 swappiness [$swappiness]"
        set_value "$swappiness" /proc/sys/vm/swappiness
        for p in \
            /dev/memcg/memory.swappiness \
            /dev/memcg/apps/memory.swappiness \
            /sys/fs/cgroup/memory/apps/memory.swappiness \
            /sys/fs/cgroup/memory/memory.swappiness
        do
            [ -e "$p" ] && set_value "$swappiness" "$p"
        done
    fi
}

set_kswapd_cpus() {
    [ -n "$kswapd_cpus" ] || return 0
    [ -n "$TASKSET_BIN" ] || return 0
    case "$kswapd_cpus" in
        *[!01]*)
            echo "kswapd_cpus 格式非法: [$kswapd_cpus]，跳过"
            return 1
            ;;
    esac

    mask=0
    idx=0
    rest="$kswapd_cpus"
    while [ -n "$rest" ]; do
        ch=${rest%"${rest#?}"}
        rest=${rest#?}
        if [ "$ch" = "1" ]; then
            mask=$((mask + (1 << idx)))
        fi
        idx=$((idx + 1))
    done
    [ "$mask" -gt 0 ] || return 0

    hex_mask=$(printf '%x' "$mask")
    for kpid in $(pgrep kswapd 2>/dev/null); do
        echo "设置 kswapd($kpid) CPU 亲和掩码: 0x$hex_mask"
        $TASKSET_BIN -p "$hex_mask" "$kpid" >/dev/null 2>&1
    done
}

set_zram
set_swap
set_vm_params
set_kswapd_cpus

echo "全部完成!"
