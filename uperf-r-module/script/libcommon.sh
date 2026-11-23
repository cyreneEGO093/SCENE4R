#!/system/bin/sh
#
# Copyright (C) 2021-2022 Matt Yang
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
# Basic tool functions
###############################

# $1:value $2:filepaths
lock_val() {
    for p in $2; do
        if [ -f "$p" ] && [ ! -L "$p" ]; then
            chown 0:0 "$p" 2>/dev/null
            chmod 0644 "$p" 2>/dev/null
            echo "$1" >"$p" 2>/dev/null
            chmod 0444 "$p" 2>/dev/null
        fi
    done
}

# $1:value $2:filepaths (writes without leaving 0666 or locking 0444)
mask_val() {
    for p in $2; do
        if [ -f "$p" ] && [ ! -L "$p" ]; then
            chmod 0644 "$p" 2>/dev/null
            echo "$1" >"$p" 2>/dev/null
        fi
    done
}

# $1:value $2:filepaths (safe mutate without leaving 0666)
mutate() {
    for p in $2; do
        if [ -f "$p" ] && [ ! -L "$p" ]; then
            chmod 0644 "$p" 2>/dev/null
            echo "$1" >"$p" 2>/dev/null
        fi
    done
}

# $1:file path
lock() {
    if [ -f "$1" ] && [ ! -L "$1" ]; then
        chown 0:0 "$1" 2>/dev/null
        chmod 0444 "$1" 2>/dev/null
    fi
}

# $1:value $2:list
has_val_in_list() {
    for item in $2; do
        if [ "$1" = "$item" ]; then
            echo "true"
            return
        fi
    done
    echo "false"
}

###############################
# Config File Operator
###############################

# $1:key $return:value(string)
read_cfg_value() {
    local value=""
    if [ -f "$PANEL_FILE" ] && [ ! -L "$PANEL_FILE" ]; then
        value="$(grep -i "^$1=" "$PANEL_FILE" | head -n 1 | tr -d ' ' | cut -d= -f2)"
    fi
    echo "$value"
}

# $1:content
write_panel() {
    [ -L "$PANEL_FILE" ] && return 1
    echo "$1" >>"$PANEL_FILE"
}

clear_panel() {
    [ -L "$PANEL_FILE" ] && return 1
    true >"$PANEL_FILE"
}

wait_until_boot_completed() {
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 1
    done
}

###############################
# Log
###############################

# $1:content
log() {
    [ -L "$USER_PATH" ] && return 1
    [ -L "$LOG_FILE" ] && return 1
    echo "$1" >>"$LOG_FILE"
}

clear_log() {
    [ -L "/data/adb" ] && return 1
    [ -L "$USER_PATH" ] && return 1
    mkdir -p "$USER_PATH" || return 1
    chown 0:0 "$USER_PATH" || return 1
    chmod 0700 "$USER_PATH" || return 1
    [ -L "$LOG_FILE" ] && return 1
    true >"$LOG_FILE" || return 1
    chown 0:0 "$LOG_FILE" || return 1
    chmod 0600 "$LOG_FILE" || return 1
}
