#!/system/bin/sh

install_path="$1"

busybox_install() {
  chmod 0755 ./busybox || return 1
  applets=$(./busybox --list) || return 1
  for applet in $applets; do
    case "$applet" in
    "sh"|"busybox"|"shell"|"swapon"|"swapoff"|"mkswap"|*/*|"."|"..")
      ;;
    *)
      ./busybox ln -sf busybox "$applet" || return 1
      ;;
    esac
  done
  ./busybox ln -sf busybox busybox_1_30_1 || return 1
}

if [ -n "$install_path" ] && [ -d "$install_path" ]; then
  cd "$install_path" || exit 1
  chmod 0755 ./busybox || exit 1
  if [ ! -f busybox_1_30_1 ] || [ ! -f md5sum ] || [ ! -x ./busybox ]; then
    busybox_install || exit 1
  fi
else
  exit 1
fi
