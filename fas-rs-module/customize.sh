#!/system/bin/sh
# Hardened installer for FAS-RS (SCENE4R Edition, GNU GPL v3.0)
SKIPUNZIP=0

if [ "$BOOTMODE" != "true" ]; then
    abort "! FAS-RS cannot be installed in recovery."
fi

if [ "$ARCH" != "arm64" ]; then
    abort "! FAS-RS ONLY supports arm64 platform."
fi

umask 077

ui_print "***************************************"
ui_print "    FAS-RS (SCENE4R modify) v4.9.1-r1  "
ui_print "          License: GNU GPL v3.0        "
ui_print "***************************************"
ui_print "- 运行边界隔离至 Root 专属 /data/adb/fas-rs (0700)"
ui_print "- 已禁用 Root LuaJIT 扩展插件与全局渲染 prop 覆盖"
ui_print "- 完整保留系统原厂温控策略"

if [ -d /sys/fs/bpf ]; then
    ui_print "- 检测到内核 BPF 支持 (/sys/fs/bpf)"
else
    ui_print "! 警告：未检测到 /sys/fs/bpf，部分内核可能不支持 FAS-RS"
fi

[ -L "/data/adb" ] && abort "! Refusing symlink /data/adb"
[ -L "/data/adb/fas-rs" ] && abort "! Refusing symlink /data/adb/fas-rs"

mkdir -p /data/adb/fas-rs/node /data/adb/fas-rs/.mount_masks || abort "! Failed to create /data/adb/fas-rs"
[ -d /data/adb/fas-rs ] && [ ! -L /data/adb/fas-rs ] || abort "! Unsafe /data/adb/fas-rs directory"
[ -d /data/adb/fas-rs/node ] && [ ! -L /data/adb/fas-rs/node ] || abort "! Unsafe /data/adb/fas-rs/node"
[ -d /data/adb/fas-rs/.mount_masks ] && [ ! -L /data/adb/fas-rs/.mount_masks ] || abort "! Unsafe .mount_masks"

chown 0:0 /data/adb/fas-rs /data/adb/fas-rs/node /data/adb/fas-rs/.mount_masks
chmod 0700 /data/adb/fas-rs /data/adb/fas-rs/node /data/adb/fas-rs/.mount_masks

USER_CFG="/data/adb/fas-rs/games.toml"
[ -L "$USER_CFG" ] && abort "! Refusing symlink $USER_CFG"

if [ -f "$USER_CFG" ]; then
    ui_print "- 保留已有用户配置并在启动时验证合并: $USER_CFG"
    touch "$MODPATH/.need_merge"
    chown 0:0 "$USER_CFG"
    chmod 0600 "$USER_CFG"
else
    ui_print "- 初始化默认配置: $USER_CFG"
    cp -f "$MODPATH/games.toml" "$USER_CFG" || abort "! Failed to copy games.toml"
    chown 0:0 "$USER_CFG"
    chmod 0600 "$USER_CFG"
fi

MODE_NODE="/data/adb/fas-rs/node/mode"
[ -L "$MODE_NODE" ] && abort "! Refusing symlink $MODE_NODE"
if [ ! -f "$MODE_NODE" ]; then
    printf 'balance\n' >"$MODE_NODE"
fi
chown 0:0 "$MODE_NODE"
chmod 0600 "$MODE_NODE"

chown 0:0 "$MODPATH/bin/fas-rs" "$MODPATH/service.sh" "$MODPATH/uninstall.sh" "$MODPATH/games.toml" "$MODPATH/module.prop" "$MODPATH/LICENSE"
chmod 0755 "$MODPATH/bin/fas-rs" "$MODPATH/service.sh" "$MODPATH/uninstall.sh"
chmod 0644 "$MODPATH/games.toml" "$MODPATH/module.prop" "$MODPATH/LICENSE"

ui_print "- 安装完成：可在 SCENE4R「概览 / CPU模式」随时在 Uperf-R 与 FAS-RS 间切换"
