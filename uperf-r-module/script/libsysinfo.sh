#!/system/bin/sh
#
# Copyright (C) 2021-2022 Matt Yang & yinwanxi
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

###############################
# Platform info functions
###############################

soc_model() {
    getprop ro.soc.model
}

match_linux_version() {
    grep -i "$1" /proc/version
}

get_socid() {
    if [ -f /sys/devices/soc0/soc_id ]; then
        cat /sys/devices/soc0/soc_id
    elif [ -f /sys/devices/system/soc/soc0/id ]; then
        cat /sys/devices/system/soc/soc0/id
    fi
}

get_nr_core() {
    grep -c "cpu[0-9]" /proc/stat
}

# $1:cpuid
get_maxfreq() {
    cat "/sys/devices/system/cpu/cpu$1/cpufreq/cpuinfo_max_freq" 2>/dev/null || echo 0
}

get_socname() {
    if [ -f /sys/devices/soc0/machine ]; then
        tr '[:upper:]' '[:lower:]' < /sys/devices/soc0/machine
    fi
}

is_aarch64() {
    if [ "$(getprop ro.product.cpu.abi)" = "arm64-v8a" ]; then
        echo "true"
    else
        echo "false"
    fi
}

is_eas() {
    if [ -n "$(grep sched /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors 2>/dev/null)" ]; then
        echo "true"
    else
        echo "false"
    fi
}

is_mtk() {
    local hw_check
    hw_check="$(getprop ro.hardware) $(getprop ro.board.platform) $(getprop ro.boot.hardware) $(getprop ro.soc.manufacturer) $(getprop ro.soc.model)"
    if echo "$hw_check" | tr '[:upper:]' '[:lower:]' | grep -qE '(mt[0-9]{4}|mediatek|dimensity|helio[ _-]g?[0-9]{2})'; then
        echo "true"
    else
        echo "false"
    fi
}

miui_check() {
    if [ -n "$(getprop ro.miui.ui.version.name)" ] || [ -n "$(getprop ro.mi.os.version.name)" ]; then
        echo "true"
    else
        echo "false"
    fi
}

_get_sm6150_type() {
    case "$(get_socid)" in
    365 | 366) echo "sdm730" ;;
    355 | 369) echo "sdm675" ;;
    *) echo "unsupported" ;;
    esac
}

_get_sdm76x_type() {
    if [ "$(get_maxfreq 7)" -gt 2300000 ]; then
        echo "sdm765"
    else
        echo "sdm750"
    fi
}

_get_lahaina_type() {
    if [ "$(get_maxfreq 7)" -gt 2600000 ]; then
        echo "sdm888"
    elif [ "$(get_maxfreq 4)" -gt 2300000 ]; then
        echo "sdm778"
    else
        echo "sdm780"
    fi
}

_get_taro_type() {
    local socn
    socn="$(get_socname)"
    if [ "$socn" = "cape" ] || [ "$socn" = "capep" ] || [ "$socn" = "ukee" ] || [ "$socn" = "ukeep" ]; then
        echo "sdm8g1+"
    elif [ "$socn" = "waipio" ] || [ "$socn" = "waipiop" ]; then
        echo "sdm8g1"
    else
        echo "sdm7g1"
    fi
}

_get_pineapple_type() {
    case "$(soc_model)" in
    SM8650*)
        if [ "$(soc_model)" = "SM8650Q" ] || [ "$(get_nr_core)" -eq 6 ]; then
            echo "sdm8g3q"
        else
            echo "sdm8g3"
        fi
        ;;
    SM8635*) echo "sdm8sg3" ;;
    SM7675*) echo "sdm7+g3" ;;
    *)
        if [ "$(get_nr_core)" -eq 6 ]; then
            echo "sdm8g3q"
        elif [ "$(get_maxfreq 7)" -ge 3150000 ]; then
            echo "sdm8g3"
        else
            echo "sdm7+g3"
        fi
        ;;
    esac
}

_get_kalama_type() {
    if [ "$(get_maxfreq 7)" -gt 3300000 ]; then
        echo "sdm8cxg2"
    else
        echo "sdm8g2"
    fi
}

_get_sun_type() {
    if [ "$(soc_model)" = "SM8735" ]; then
        echo "sdm8sg4"
    else
        echo "sdm8e"
    fi
}

_get_rk30sdk() {
    if [ -d /sys/devices/system/cpu/cpu7 ]; then
        echo "rk3588"
    else
        echo "rk356"
    fi
}

# $1:board_name
get_config_name() {
    case "$1" in
    "rk30sdk") _get_rk30sdk ;;
    "pineapple") _get_pineapple_type ;;
    "kalama") _get_kalama_type ;;
    "taro") _get_taro_type ;;
    "lahaina" | "shima" | "yupik") _get_lahaina_type ;;
    "sun") _get_sun_type ;;
    "canoe") echo "sdm8eg5" ;;
    "volcano") echo "sdm7sg3" ;;
    "cliffs") echo "sdm7+g3" ;;
    "kona") echo "sdm865" ;;
    "msmnile") echo "sdm855" ;;
    "sdm845") echo "sdm845" ;;
    "lito") _get_sdm76x_type ;;
    "sm6150") _get_sm6150_type ;;
    "holi") echo "sdm695" ;;
    "sdm710") echo "sdm710" ;;
    "atoll" | "curtana") echo "sdm720" ;;
    "msm8960") echo "sdm600" ;;
    "msm8939" | "msm8916" | "MSM8916") echo "sdm616" ;;
    "sky" | "parrot") echo "sdm4g2" ;;
    "msm8937") echo "sdm439" ;;
    "msm8953" | "msm8953pro") echo "sdm625" ;;
    "msm8952Pro" | "msm8952" | "msm8976SG") echo "sdm653" ;;
    "sdm660" | "sdm636") echo "sdm660" ;;
    "trinket") echo "sdm665" ;;
    "bengal") echo "sdm680" ;;
    "msm8992") echo "sdm808" ;;
    "msm8998") echo "sdm835" ;;
    "msm8996" | "msm8996pro") echo "sdm820" ;;
    "msm8909") echo "sdm210" ;;
    "exynos850") echo "e850" ;;
    "exynos5" | "universal8895") echo "e8895" ;;
    "s5e9925" | "universal9925") echo "e2200" ;;
    "exynos2100") echo "e2100" ;;
    "s5e8825" | "erd8825") echo "e1280" ;;
    "erd8835") echo "e1380" ;;
    "exynos1080" | "universal1080" | "s5e9815" | "erd9815_rt") echo "e1080" ;;
    "exynos990" | "universal990") echo "e990" ;;
    "exynos7884B" | "universal7884" | "universal7884B") echo "e7884" ;;
    "universal5420") echo "e5420" ;;
    "universal9825" | "universal9820") echo "e9820" ;;
    "universal9810") echo "e9810" ;;
    "universal8890") echo "e8890" ;;
    "universal7420") echo "e7420" ;;
    "mt6580") echo "mt6580" ;;
    "mt6765") echo "mtp35" ;;
    "mt6768") echo "mtg80" ;;
    "mt6750" | "OPPO R9m") echo "mtp10" ;;
    "mt6757" | "k57pv1_dm_64") echo "mtp20" ;;
    "mt6739") echo "mt6739" ;;
    "mt6755") echo "mt6755" ;;
    "mt6781" | "viva" | "fleur") echo "mt6781" ;;
    "mt6761") echo "mta22" ;;
    "mt6771") echo "mtp70" ;;
    "mt8167") echo "mt8167" ;;
    "mt8175" | "mt8173") echo "mt8173" ;;
    "mt6779") echo "mtp90" ;;
    "mt6789") echo "mtg99" ;;
    "mt6785") echo "mtg90t" ;;
    "Infinix-X6827" | "mt6827") echo "mtg96" ;;
    "mt6795") echo "mtx10" ;;
    "mt6797") echo "mtx20" ;;
    "mt6799") echo "mtx30" ;;
    "mt6833" | "mt6833p" | "mt6833v" | "mt6853") echo "mtd720" ;;
    "mt6835") echo "mtd6000" ;;
    "mt6873" | "mt6875") echo "mtd820" ;;
    "mt6877") echo "mtd920" ;;
    "mt6855" | "cancunf") echo "mtd930" ;;
    "mt6885" | "mt6889") echo "mtd1000" ;;
    "mt6886") echo "mtd7000" ;;
    "mt6878") echo "mtd7300" ;;
    "mt6893" | "rm6893") echo "mtd1200" ;;
    "mt6895") echo "mtd8100" ;;
    "mt6897") echo "mtd8300" ;;
    "mt6899" | "mt6899v1_64") echo "mtd8400" ;;
    "mt6983") echo "mtd9000" ;;
    "mt6985") echo "mtd9200" ;;
    "mt6989") echo "mtd9300" ;;
    "mt6991") echo "mtd9400" ;;
    "mt6993") echo "mtd9500" ;;
    "gs101") echo "gs101" ;;
    "gs201" | "cheetah") echo "gs201" ;;
    "husky" | "zuma" | "shiba") echo "gs301" ;;
    "comet" | "caiman" | "tokay" | "komodo" | "zumapro") echo "gs401" ;;
    "PRL" | "BLN" | "hi6250" | "kirin655" | "kirin658" | "kirin659") echo "kirin65x" ;;
    "MAR" | "INE" | "ANE" | "JSN" | "kirin710" | "JKM-AL20") echo "kirin710" ;;
    "STF" | "HWI" | "kirin960" | "hi3660" | "CMR") echo "kirin960" ;;
    "BKL" | "ALP" | "COR" | "kirin970") echo "kirin970" ;;
    "ELE" | "kirin980") echo "kirin980" ;;
    "kirin990" | "LIO-AL00" | "OXP-AN00" | "ANA-AN00" | "ELS-AN10" | "EBG-AN00" | "TAS-AN00" | "LIO-AN00P" | "OXF-AN00" | "OCE-AL50" | "SCMR-W09" | "TAH-AN00m") echo "kirin990" ;;
    "kirin9000" | "JAD") echo "kirin9000" ;;
    "hi3650" | "FRD" | "EVA-TL00" | "kirin955") echo "kirin955" ;;
    "sp9863a" | "sp9863a_1h10") echo "sp9863" ;;
    "ums312" | "ums312_2h10") echo "t310" ;;
    "ums9230" | "ums9230_1h10") echo "t606" ;;
    "ums9621" | "m2521") echo "t8200" ;;
    "ums512" | "ums512_1h10") echo "t618" ;;
    "ud710" | "ud710_7h10") echo "t710" ;;
    "ums9620" | "ums9620_2h10") echo "t770" ;;
    "sp9832e" | "sp9832e_1h10") echo "sp9832" ;;
    "tegra" | "dragon" | "tegraa210_dragon") echo "tegra" ;;
    "latte" | "gmin") echo "z8500" ;;
    "O1_asic") echo "xro1" ;;
    "song") echo "song1" ;;
    "rk356x") echo "rk356" ;;
    "rk3588") echo "rk3588" ;;
    *)
        # Fallback by ro.soc.model (helpful on Android 16+ devices)
        case "$(soc_model)" in
        SM8650Q) echo "sdm8g3q" ;;
        SM8650*) echo "sdm8g3" ;;
        SM8635*) echo "sdm8sg3" ;;
        SM8550*) echo "sdm8g2" ;;
        SM8750*) echo "sdm8e" ;;
        *) echo "unsupported" ;;
        esac
        ;;
    esac
}
