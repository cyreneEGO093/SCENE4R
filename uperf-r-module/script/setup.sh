#!/system/bin/sh
#
# Hardened installer setup for Uperf-R (SCENE4R Edition)
# - Shares .import.lock with runtime importer and requires exclusive mktemp staging
# - Validates new profile BEFORE touching active uperf.json
# - Checks exact 4096-byte page size requirement (upstream 4 KiB binary limitation)
#

BASEDIR="$(dirname "$(readlink -f "$0")")"
. "$BASEDIR/pathinfo.sh"
. "$BASEDIR/libsysinfo.sh"

umask 077
TARGET_MODPATH="${MODPATH:-$MODULE_PATH}"
SETUP_LOCK_DIR=""
SETUP_STAGE_CFG=""

cleanup_setup() {
    [ -n "$SETUP_STAGE_CFG" ] && rm -f "$SETUP_STAGE_CFG"
    [ -n "$SETUP_LOCK_DIR" ] && rmdir "$SETUP_LOCK_DIR" 2>/dev/null || true
    SETUP_STAGE_CFG=""
    SETUP_LOCK_DIR=""
}

abort() {
    echo "$1"
    echo "! Uperf-R installation failed."
    exit 1
}

set_perm() {
    chown "$2:$3" "$1" || abort "! chown failed: $1"
    chmod "$4" "$1" || abort "! chmod failed: $1"
    chcon "$5" "$1" 2>/dev/null
}

set_perm_recursive() {
    find "$1" -type d 2>/dev/null | while read -r dir; do
        set_perm "$dir" "$2" "$3" "$4" "$6"
    done
    find "$1" \( -type f -o -type l \) 2>/dev/null | while read -r file; do
        set_perm "$file" "$2" "$3" "$5" "$6"
    done
}

check_page_size() {
    local psize rc
    psize="$(getconf PAGESIZE 2>/dev/null || getconf PAGE_SIZE 2>/dev/null)"
    rc=$?
    if [ "$rc" -eq 0 ] && [ "$psize" = "4096" ]; then
        echo "- Kernel page size: 4096 bytes (supported)"
    else
        echo "! Warning: host PAGESIZE probe rc=$rc val='$psize'; pinned upstream bin/uperf requires exact 4096-byte pages and runtime service will fail closed."
    fi
}

check_conflicts() {
    for mod in ct_module asoul_affinity_opt unity_affinity_opt scene_powercfg; do
        if [ -d "/data/adb/modules/$mod" ] && [ ! -f "/data/adb/modules/$mod/disable" ]; then
            echo "! Warning: potentially conflicting module detected: $mod"
            echo "! Consider disabling $mod in your root manager if scheduling conflicts occur."
        fi
    done
}

install_uperf() {
    echo "- Detecting SoC configuration"
    echo "- ro.board.platform=$(getprop ro.board.platform)"
    echo "- ro.product.board=$(getprop ro.product.board)"
    echo "- ro.soc.model=$(getprop ro.soc.model)"

    local target cfgname
    target="$(getprop ro.board.platform)"
    cfgname="$(get_config_name "$target")"
    if [ "$cfgname" = "unsupported" ]; then
        target="$(getprop ro.product.board)"
        cfgname="$(get_config_name "$target")"
    fi
    if [ "$cfgname" = "unsupported" ]; then
        target="$(getprop ro.soc.model)"
        cfgname="$(get_config_name "$target")"
    fi

    if [ "$cfgname" = "unsupported" ] || [ ! -f "$TARGET_MODPATH/config/$cfgname.json" ]; then
        abort "! Target [$target] is not supported."
    fi

    echo "- Selected profile: $cfgname"
    echo "- Installing root-only runtime config at $USER_PATH"

    [ -L "/data/adb" ] && abort "! Unsafe symlink at /data/adb"
    [ -L "$USER_PATH" ] && abort "! Unsafe symlink at $USER_PATH"
    mkdir -p "$USER_PATH" || abort "! Failed to create $USER_PATH"
    [ -d "$USER_PATH" ] && [ ! -L "$USER_PATH" ] || abort "! Invalid $USER_PATH"
    chown 0:0 "$USER_PATH" && chmod 0700 "$USER_PATH" || abort "! Failed to secure $USER_PATH"

    local bb="$TARGET_MODPATH/bin/busybox/busybox"
    # Ensure extracted binaries & scripts have executable mode BEFORE invoking busybox
    chown 0:0 "$bb" "$TARGET_MODPATH/bin/uperf" 2>/dev/null || true
    chmod 0755 "$bb" "$TARGET_MODPATH/bin/uperf" || abort "! Failed to set binary executable mode"
    set_perm_recursive "$TARGET_MODPATH/bin" 0 0 0755 0755 u:object_r:system_file:s0
    set_perm_recursive "$TARGET_MODPATH/script" 0 0 0755 0755 u:object_r:system_file:s0
    [ -f "$bb" ] && [ ! -L "$bb" ] && [ -x "$bb" ] || abort "! Missing or non-executable pinned busybox: $bb"

    SETUP_LOCK_DIR="$USER_PATH/.import.lock"
    mkdir "$SETUP_LOCK_DIR" 2>/dev/null || { SETUP_LOCK_DIR=""; abort "! Another config writer holds $USER_PATH/.import.lock"; }
    trap cleanup_setup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    for f in uperf.json uperf.json.bak uperf.default.json perapp_powermode.txt cur_powermode.txt; do
        [ -L "$USER_PATH/$f" ] && abort "! Unsafe symlink at $USER_PATH/$f"
    done

    SETUP_STAGE_CFG="$("$bb" mktemp "$USER_PATH/.uperf_stage.XXXXXX" 2>/dev/null)"
    [ -n "$SETUP_STAGE_CFG" ] && [ -f "$SETUP_STAGE_CFG" ] && [ ! -L "$SETUP_STAGE_CFG" ] || abort "! Exclusive staging failed"

    cp -f "$TARGET_MODPATH/config/$cfgname.json" "$SETUP_STAGE_CFG" || abort "! Failed to stage $cfgname.json"
    chown 0:0 "$SETUP_STAGE_CFG" && chmod 0600 "$SETUP_STAGE_CFG" || abort "! Failed to secure stage file"

    "$bb" awk -f "$TARGET_MODPATH/script/validate_config.awk" "$SETUP_STAGE_CFG" || abort "! Profile $cfgname failed validation; keeping prior configuration untouched."

    if [ -f "$USER_PATH/uperf.json" ]; then
        cp -f "$USER_PATH/uperf.json" "$USER_PATH/uperf.json.bak" || abort "! Failed to backup prior uperf.json"
        chown 0:0 "$USER_PATH/uperf.json.bak" && chmod 0600 "$USER_PATH/uperf.json.bak" || abort "! Failed to secure uperf.json.bak"
        if "$bb" awk -f "$TARGET_MODPATH/script/validate_config.awk" "$USER_PATH/uperf.json" >/dev/null 2>&1; then
            echo "- Existing valid $USER_PATH/uperf.json preserved across upgrade (default saved to $USER_PATH/uperf.default.json)"
            mv -f "$SETUP_STAGE_CFG" "$USER_PATH/uperf.default.json" || abort "! Failed to store default profile"
            SETUP_STAGE_CFG=""
            chown 0:0 "$USER_PATH/uperf.default.json" && chmod 0600 "$USER_PATH/uperf.default.json" || abort "! chmod failed"
        else
            mv -f "$SETUP_STAGE_CFG" "$USER_PATH/uperf.json" || abort "! Failed to publish uperf.json"
            SETUP_STAGE_CFG=""
        fi
    else
        mv -f "$SETUP_STAGE_CFG" "$USER_PATH/uperf.json" || abort "! Failed to publish uperf.json"
        SETUP_STAGE_CFG=""
    fi

    cleanup_setup

    if [ ! -e "$USER_PATH/perapp_powermode.txt" ]; then
        cp -f "$TARGET_MODPATH/config/perapp_powermode.txt" "$USER_PATH/perapp_powermode.txt" || abort "! Failed to copy perapp_powermode.txt"
    fi
    [ -e "$USER_PATH/cur_powermode.txt" ] || printf 'balance\n' >"$USER_PATH/cur_powermode.txt"

    chown 0:0 "$USER_PATH/uperf.json" "$USER_PATH/perapp_powermode.txt" "$USER_PATH/cur_powermode.txt" || abort "! chown failed"
    chmod 0600 "$USER_PATH/uperf.json" "$USER_PATH/perapp_powermode.txt" "$USER_PATH/cur_powermode.txt" || abort "! chmod failed"

    rm -rf "$TARGET_MODPATH/config"
}

echo "*****************************************************"
echo "* Uperf-R (SCENE4R Edition)"
echo "* Base: yc9559/uperf (dev-22.09.04) + 120 SoC profiles"
echo "* Thermal policy: OEM system thermal PRESERVED"
echo "*****************************************************"

check_page_size
check_conflicts
install_uperf

echo "- Installation complete. Reboot to activate Uperf."
