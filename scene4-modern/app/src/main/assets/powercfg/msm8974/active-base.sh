#!/system/bin/sh

echo 1 > /sys/devices/system/cpu/cpu0/online
echo 1 > /sys/devices/system/cpu/cpu1/online
echo 1 > /sys/devices/system/cpu/cpu2/online
echo 1 > /sys/devices/system/cpu/cpu3/online

# [thermal preserved] stop thermal-engine

# [thermal preserved] echo 0 > /sys/module/msm_thermal/core_control/enabled
# [thermal preserved] echo 0 > /sys/module/msm_thermal/vdd_restriction/enabled
# [thermal preserved] echo N > /sys/module/msm_thermal/parameters/enabled
