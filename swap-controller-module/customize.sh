#!/system/bin/sh
SKIPUNZIP=0

apply_perm() {
    chown "$2:$3" "$1" 2>/dev/null
    chmod "$4" "$1" 2>/dev/null
}

ui_print "*******************************"
ui_print "   SCENE4R SWAP 控制器 v4.0.0  "
ui_print "     License: GNU GPL v3.0     "
ui_print "*******************************"
ui_print "- 适配 Android 16 / cgroup v2"
ui_print "- 保留原厂 perfconfigstore.xml 与 LMKD 参数"

swap_config="/data/swap_config.conf"
if [ -f "$swap_config" ]; then
    ui_print "- 保留已有配置: $swap_config"
    append_missing_key() {
        key="$1"
        val="$2"
        if ! grep -q "^${key}=" "$swap_config" 2>/dev/null; then
            printf '%s=%s\n' "$key" "$val" >> "$swap_config"
        fi
    }
    append_missing_key "swap" "false"
    append_missing_key "swap_size" "2048"
    append_missing_key "swap_priority" "-2"
    append_missing_key "swap_use_loop" "false"
    append_missing_key "zram" "false"
    append_missing_key "zram_size" "2048"
    append_missing_key "comp_algorithm" "lz4"
    append_missing_key "swappiness" "60"
    append_missing_key "extra_free_kbytes" ""
    append_missing_key "watermark_scale_factor" ""
    append_missing_key "kswapd_cpus" ""
    append_missing_key "daemon_service" "basic"
else
    ui_print "- 生成默认配置: $swap_config"
    cat > "$swap_config" <<'EOF'
# SCENE4R SWAP 控制器配置文件 (GPL-3.0)
swap=false
swap_size=2048
swap_priority=-2
swap_use_loop=false
zram=false
zram_size=2048
comp_algorithm=lz4
swappiness=60
extra_free_kbytes=
watermark_scale_factor=
kswapd_cpus=
daemon_service=basic
EOF
fi
apply_perm "$swap_config" 0 0 0600

apply_perm "$MODPATH/service.sh" 0 0 0755
apply_perm "$MODPATH/uninstall.sh" 0 0 0755
apply_perm "$MODPATH/system/bin/scene_swap_module.sh" 0 0 0755
apply_perm "$MODPATH/system/bin/scene_swap_extra.sh" 0 0 0755
apply_perm "$MODPATH/system/bin/scene_swap_service.dex" 0 0 0644

ui_print "- 安装完成，重启设备后生效"
