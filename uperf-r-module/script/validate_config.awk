# Strict JSON parser, duplicate-key detector, and Uperf trust-boundary validator (BusyBox awk)
# Usage: awk [-v KNOBS_OUT=/path] -f validate_config.awk <config.json>
# Enforces decoded key identity (rejects \u escapes), nesting depth <= 16 on all values,
# strict scalar/object expectations, top-level schema keys, finite/component bounds, and exact 42-path allowlist.

function fail(msg) {
    print "VALIDATION_ERROR: " msg > "/dev/stderr"
    err = 1
    exit 1
}

function skip_ws() {
    while (pos <= len) {
        ch = substr(doc, pos, 1)
        if (ch == " " || ch == "\t" || ch == "\r" || ch == "\n") {
            pos++
        } else {
            break
        }
    }
}

function parse_string(    out, c, hex4) {
    if (substr(doc, pos, 1) != "\"") fail("expected string at pos " pos)
    pos++
    out = ""
    while (pos <= len) {
        c = substr(doc, pos, 1)
        if (c == "\"") {
            pos++
            return out
        }
        if (c == "\\") {
            pos++
            if (pos > len) fail("unterminated escape")
            c = substr(doc, pos, 1)
            if (c == "\"" || c == "\\" || c == "/") out = out c
            else if (c == "b") out = out "\b"
            else if (c == "f") out = out "\f"
            else if (c == "n") out = out "\n"
            else if (c == "r") out = out "\r"
            else if (c == "t") out = out "\t"
            else if (c == "u") {
                hex4 = substr(doc, pos + 1, 4)
                fail("unicode escape \\u" hex4 " not permitted in trusted config at pos " pos)
            } else {
                fail("invalid escape \\" c " at pos " pos)
            }
            pos++
        } else {
            if (c in CTRL) fail("control char in string at pos " pos)
            out = out c
            pos++
        }
    }
    fail("unterminated string")
}

function parse_number(    start, num) {
    start = pos
    if (substr(doc, pos, 1) == "-") pos++
    if (substr(doc, pos, 1) == "0") {
        pos++
    } else if (substr(doc, pos, 1) ~ /[1-9]/) {
        while (pos <= len && substr(doc, pos, 1) ~ /[0-9]/) pos++
    } else {
        fail("invalid number at pos " pos)
    }
    if (pos <= len && substr(doc, pos, 1) == ".") {
        pos++
        if (pos > len || substr(doc, pos, 1) !~ /[0-9]/) fail("invalid fraction at pos " pos)
        while (pos <= len && substr(doc, pos, 1) ~ /[0-9]/) pos++
    }
    if (pos <= len && (substr(doc, pos, 1) == "e" || substr(doc, pos, 1) == "E")) {
        pos++
        if (pos <= len && (substr(doc, pos, 1) == "+" || substr(doc, pos, 1) == "-")) pos++
        if (pos > len || substr(doc, pos, 1) !~ /[0-9]/) fail("invalid exponent at pos " pos)
        while (pos <= len && substr(doc, pos, 1) ~ /[0-9]/) pos++
    }
    num = substr(doc, start, pos - start)
    if (length(num) > 24) fail("number literal too long at pos " start)
    return num
}

function init_allowlist() {
    EXACT_KNOBS["/dev/cpuset/background/cpus"] = 1
    EXACT_KNOBS["/dev/cpuset/foreground/cpus"] = 1
    EXACT_KNOBS["/dev/cpuset/restricted/cpus"] = 1
    EXACT_KNOBS["/dev/cpuset/system-background/cpus"] = 1
    EXACT_KNOBS["/dev/cpuset/top-app/cpus"] = 1
    EXACT_KNOBS["/proc/cpufreq/cpufreq_cci_mode"] = 1
    EXACT_KNOBS["/sys/class/devfreq/18590100.qcom,cpu4-cpu-l3-lat/max_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/18590100.qcom,cpu4-cpu-l3-lat/min_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/1d84000.ufshc/max_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/3d00000.qcom,kgsl-3d0/max_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/3d00000.qcom,kgsl-3d0/min_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/kgsl-busmon/max_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/kgsl-busmon/min_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/mtk-dvfsrc-devfreq/userspace/set_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw/max_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw/min_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw/max_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw/min_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu0-llcc-ddr-lat/mem_latency/ratio_ceil"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu0-llcc-ddr-lat/min_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu4-llcc-ddr-lat/max_freq"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu4-llcc-ddr-lat/mem_latency/ratio_ceil"] = 1
    EXACT_KNOBS["/sys/class/devfreq/soc:qcom,cpu4-llcc-ddr-lat/min_freq"] = 1
    EXACT_KNOBS["/sys/devices/platform/boot_dramboost/dramboost/dramboost"] = 1
    EXACT_KNOBS["/sys/devices/platform/exynos-ufcc/ufc/cpufreq_max_limit"] = 1
    EXACT_KNOBS["/sys/devices/platform/exynos-ufcc/ufc/cpufreq_min_limit"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/bus_dcvs/DDR/hw_max_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/bus_dcvs/DDR/hw_min_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/bus_dcvs/L3/hw_max_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/bus_dcvs/L3/hw_min_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/bus_dcvs/LLCC/hw_max_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/bus_dcvs/LLCC/hw_min_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/cpu5/core_ctl/enable"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/cpu5/core_ctl/max_cpus"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/cpu5/core_ctl/min_cpus"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq"] = 1
    EXACT_KNOBS["/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq"] = 1
    EXACT_KNOBS["/sys/kernel/gpu/gpu_max_clock"] = 1
    EXACT_KNOBS["/sys/kernel/gpu/gpu_min_clock"] = 1

    TOP_KEYS["meta"] = 1
    TOP_KEYS["modules"] = 1
    TOP_KEYS["presets"] = 1
    TOP_KEYS["initials"] = 1

    for (ci = 1; ci < 32; ci++) {
        CTRL[sprintf("%c", ci)] = 1
    }
}

function allowed_knob_path(p) {
    if (index(p, "..") > 0) return 0
    return (p in EXACT_KNOBS)
}

function check_sysfs_scalar(path, vtype, sval,    nval, parts, nparts, i, segs, nsegs, j) {
    if (vtype == "S") {
        if (sval !~ /^[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$/ || length(sval) > 64) {
            fail("unsafe sysfs value at " path ": " sval)
        }
        nparts = split(sval, parts, ",")
        if (nparts > 16) fail("too many list segments at " path)
        for (i = 1; i <= nparts; i++) {
            nsegs = split(parts[i], segs, "-")
            for (j = 1; j <= nsegs; j++) {
                if (length(segs[j]) > 11 || (segs[j] + 0) > 10000000000) {
                    fail("out-of-range sysfs component at " path ": " sval)
                }
            }
            if (nsegs == 2 && (segs[1] + 0) > (segs[2] + 0)) {
                fail("inverted sysfs range at " path ": " sval)
            }
        }
    } else if (vtype == "N") {
        nval = sval + 0
        if (nval < 0 || nval > 10000000000) fail("out-of-range numeric sysfs value at " path ": " sval)
    } else {
        fail("invalid sysfs value type at " path)
    }
}

function check_scalar_val(path, vtype, sval,    nval) {
    if (path == "/modules/switcher/switchInode") {
        if (vtype != "S" || sval != "/data/adb/uperf/cur_powermode.txt") {
            fail("switchInode must be /data/adb/uperf/cur_powermode.txt")
        }
        has_switch = 1
    } else if (path == "/modules/switcher/perapp") {
        if (vtype != "S" || sval != "/data/adb/uperf/perapp_powermode.txt") {
            fail("perapp must be /data/adb/uperf/perapp_powermode.txt")
        }
        has_perapp = 1
    } else if (path ~ /^\/modules\/sysfs\/knob\/[^\/]+$/) {
        if (vtype != "S" || !allowed_knob_path(sval)) {
            fail("disallowed sysfs knob target at " path ": " sval)
        }
        knob_list[++knob_count] = sval
    } else if (path ~ /^\/initials\/sysfs\/[^\/]+$/ || path ~ /^\/presets\/[^\/]+\/[^\/]+\/sysfs\.[^\/]+$/) {
        check_sysfs_scalar(path, vtype, sval)
    } else if (vtype == "N") {
        nval = sval + 0
        if (nval < -10000000000 || nval > 10000000000) {
            fail("non-finite or excessive number at " path ": " sval)
        }
    }
}

function check_key_allowed(parent_path, k) {
    if (parent_path == "" && !(k in TOP_KEYS)) {
        fail("unexpected top-level key '" k "'")
    }
    if (parent_path == "/presets" && k !~ /^[A-Za-z0-9_.-]+$/) {
        fail("invalid preset mode name '" k "'")
    }
    if (index(k, "/") > 0) fail("slash not allowed in key '" k "' at " parent_path)
}

function check_value_kind(path, actual_kind) {
    if ((path == "" || path == "/meta" || path == "/modules" || path == "/modules/switcher" || path == "/modules/sysfs" || path == "/modules/sysfs/knob" || path == "/initials" || path == "/initials/sysfs" || path == "/presets" || path ~ /^\/presets\/[^\/]+$/ || path ~ /^\/presets\/[^\/]+\/[^\/]+$/) && actual_kind != "O") {
        fail("expected JSON object at " path)
    }
    if ((path == "/modules/switcher/switchInode" || path == "/modules/switcher/perapp" || path ~ /^\/modules\/sysfs\/knob\/[^\/]+$/) && actual_kind != "S") {
        fail("expected JSON string at " path)
    }
    if ((path ~ /^\/initials\/sysfs\/[^\/]+$/ || path ~ /^\/presets\/[^\/]+\/[^\/]+\/sysfs\.[^\/]+$/) && actual_kind != "S" && actual_kind != "N") {
        fail("expected scalar sysfs value at " path)
    }
}

function parse_object(path, depth,    seen, k, first) {
    delete seen
    if (substr(doc, pos, 1) != "{") fail("expected '{' at pos " pos)
    pos++
    skip_ws()
    if (substr(doc, pos, 1) == "}") {
        pos++
        return
    }
    first = 1
    while (pos <= len) {
        if (!first) {
            if (substr(doc, pos, 1) != ",") fail("expected ',' or '}' in object at pos " pos)
            pos++
            skip_ws()
        }
        first = 0
        k = parse_string()
        if (k in seen) fail("duplicate key '" k "' at " path)
        seen[k] = 1
        check_key_allowed(path, k)
        skip_ws()
        if (substr(doc, pos, 1) != ":") fail("expected ':' after key '" k "' at pos " pos)
        pos++
        parse_value(path "/" k, depth + 1)
        skip_ws()
        if (substr(doc, pos, 1) == "}") {
            pos++
            return
        }
    }
    fail("unterminated object at " path)
}

function parse_array(path, depth,    idx, first) {
    if (substr(doc, pos, 1) != "[") fail("expected '[' at pos " pos)
    pos++
    skip_ws()
    if (substr(doc, pos, 1) == "]") {
        pos++
        return
    }
    idx = 0
    first = 1
    while (pos <= len) {
        if (!first) {
            if (substr(doc, pos, 1) != ",") fail("expected ',' or ']' in array at pos " pos)
            pos++
            skip_ws()
        }
        first = 0
        parse_value(path "[" idx "]", depth + 1)
        idx++
        skip_ws()
        if (substr(doc, pos, 1) == "]") {
            pos++
            return
        }
    }
    fail("unterminated array at " path)
}

function parse_value(path, depth,    c, sval) {
    if (depth > 16) fail("max nesting depth exceeded at " path)
    skip_ws()
    if (pos > len) fail("unexpected EOF at " path)
    c = substr(doc, pos, 1)
    if (c == "{") {
        check_value_kind(path, "O")
        parse_object(path, depth)
    } else if (c == "[") {
        check_value_kind(path, "A")
        parse_array(path, depth)
    } else if (c == "\"") {
        check_value_kind(path, "S")
        sval = parse_string()
        check_scalar_val(path, "S", sval)
    } else if (c == "-" || c ~ /[0-9]/) {
        check_value_kind(path, "N")
        sval = parse_number()
        check_scalar_val(path, "N", sval)
    } else if (substr(doc, pos, 4) == "true") {
        check_value_kind(path, "B")
        pos += 4
        check_scalar_val(path, "B", "true")
    } else if (substr(doc, pos, 5) == "false") {
        check_value_kind(path, "B")
        pos += 5
        check_scalar_val(path, "B", "false")
    } else if (substr(doc, pos, 4) == "null") {
        check_value_kind(path, "0")
        pos += 4
        check_scalar_val(path, "0", "null")
    } else {
        fail("unexpected token '" c "' at " path " pos " pos)
    }
}

BEGIN {
    doc = ""
    has_switch = 0
    has_perapp = 0
    knob_count = 0
    err = 0
    init_allowlist()
}
{
    doc = doc $0 "\n"
}
END {
    if (err) exit 1
    len = length(doc)
    pos = 1
    skip_ws()
    if (substr(doc, pos, 1) != "{") fail("root must be JSON object")
    check_value_kind("", "O")
    parse_object("", 0)
    skip_ws()
    if (pos <= len) fail("trailing content after JSON root at pos " pos)
    if (!has_switch || !has_perapp) fail("missing required switcher switchInode/perapp fields")
    if (KNOBS_OUT != "") {
        for (i = 1; i <= knob_count; i++) {
            print knob_list[i] > KNOBS_OUT
        }
        close(KNOBS_OUT)
    }
}
