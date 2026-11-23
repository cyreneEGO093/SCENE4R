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

MODDIR=${0%/*}

if [ -f "$MODDIR/flag/need_recuser" ]; then
    true >"$MODDIR/disable"
    if sh "$MODDIR/uninstall.sh" --rollback-bridge-only >/dev/null 2>&1; then
        rm -f "$MODDIR/flag/need_recuser" "$MODDIR/flag/rollback_failed"
    else
        # Disable module cleanly while surfacing rollback failure marker for manual inspection
        true >"$MODDIR/flag/rollback_failed"
    fi
else
    mkdir -p "$MODDIR/flag"
    true >"$MODDIR/flag/need_recuser"
fi
