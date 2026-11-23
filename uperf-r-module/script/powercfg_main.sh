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

BASEDIR="$(dirname "$(readlink -f "$0")")"
. "$BASEDIR/pathinfo.sh"

# Fail closed if runtime dir or state inode is a symlink or cannot be secured
[ -L "$USER_PATH" ] && exit 1
mkdir -p "$USER_PATH" || exit 1
chown 0:0 "$USER_PATH" || exit 1
chmod 0700 "$USER_PATH" || exit 1

target="$USER_PATH/cur_powermode.txt"
[ -L "$target" ] && exit 1

action="$1"
case "$action" in
"powersave" | "balance" | "performance" | "fast" | "auto")
    printf '%s\n' "$action" >"$target" || exit 1
    ;;
"pedestal")
    printf 'crazy\n' >"$target" || exit 1
    ;;
"init")
    printf 'balance\n' >"$target" || exit 1
    ;;
*)
    echo "Failed to apply unknown action '$action'." >&2
    exit 1
    ;;
esac

chown 0:0 "$target" && chmod 0600 "$target" || exit 1
