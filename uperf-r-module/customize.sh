#!/system/bin/sh
# Hardened Uperf installer entry for Magisk / KernelSU / APatch
SKIPUNZIP=0

if [ "$BOOTMODE" != "true" ]; then
    abort "! Uperf cannot be installed in recovery."
fi

if [ "$ARCH" != "arm64" ]; then
    abort "! Uperf ONLY supports arm64 platform."
fi

export MODPATH
sh "$MODPATH/script/setup.sh" || abort "! Setup failed."
