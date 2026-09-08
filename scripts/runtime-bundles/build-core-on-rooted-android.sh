#!/usr/bin/env bash
set -euo pipefail

# Build the portable Core bundle on a rooted ARM64 Android phone. The phone is
# only the build host: the archive is made from an official Ubuntu ARM64 rootfs.
ADB_SERIAL="${ADB_SERIAL:-}"
REMOTE="${POCKETDEV_REMOTE_DIR:-/data/adb/pocketdev-bundle}"
VERSION="${POCKETDEV_CORE_VERSION:-2026.09.5}"
DNS_SERVER="${POCKETDEV_DNS:-1.1.1.1}"
ROOTFS_FILE="ubuntu-base-20.04.5-base-arm64.tar.gz"
ROOTFS_SHA256="f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/${ROOTFS_FILE}"

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then adb -s "$ADB_SERIAL" "$@"; else adb "$@"; fi
}

[[ "$(uname -m)" == "arm64" || "$(uname -m)" == "aarch64" ]] || {
  echo "Run this from an ARM64 workstation controlling the rooted phone." >&2
  exit 1
}
adb_cmd wait-for-device
adb_cmd shell "su -c 'id -u'" | grep -qx 0 || { echo "ADB root is unavailable" >&2; exit 1; }

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
curl -fL --retry 3 -o "$temp_dir/$ROOTFS_FILE" "$ROOTFS_URL"
printf '%s  %s\n' "$ROOTFS_SHA256" "$temp_dir/$ROOTFS_FILE" | shasum -a 256 -c -
adb_cmd push "$temp_dir/$ROOTFS_FILE" /data/local/tmp/"$ROOTFS_FILE"

adb_cmd shell "su -c 'rm -rf $REMOTE; mkdir -p $REMOTE/rootfs $REMOTE/output; toybox tar -xzf /data/local/tmp/$ROOTFS_FILE -C $REMOTE/rootfs; rm -f /data/local/tmp/$ROOTFS_FILE'"
adb_cmd shell "su -c 'mount --bind /dev $REMOTE/rootfs/dev; mount -t proc proc $REMOTE/rootfs/proc; mount -t sysfs sysfs $REMOTE/rootfs/sys'"

guest() {
  local command="$1"
  adb_cmd shell "su -c 'chroot $REMOTE/rootfs /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -lc \"$command\"'"
}

guest "printf 'nameserver $DNS_SERVER\\n' > /etc/resolv.conf; apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade"
guest "DEBIAN_FRONTEND=noninteractive apt-get install -y git ca-certificates curl wget unzip zip xz-utils zstd"
guest "set -e; NODE_VERSION=v24.19.0; NODE_FILE=node-\\${NODE_VERSION}-linux-arm64.tar.gz; cd /tmp; curl -fsSLO https://nodejs.org/dist/\\${NODE_VERSION}/\\${NODE_FILE}; curl -fsSL https://nodejs.org/dist/\\${NODE_VERSION}/SHASUMS256.txt | grep \\\"  \\${NODE_FILE}\\\" | sha256sum -c -; mkdir -p /usr/local/lib/nodejs; tar -xzf \\${NODE_FILE} -C /usr/local/lib/nodejs; ln -sfn /usr/local/lib/nodejs/node-\\${NODE_VERSION} /usr/local/lib/nodejs/current; ln -sfn /usr/local/lib/nodejs/current/bin/node /usr/local/bin/node; ln -sfn /usr/local/lib/nodejs/current/bin/npm /usr/local/bin/npm; ln -sfn /usr/local/lib/nodejs/current/bin/npx /usr/local/bin/npx; rm -f /tmp/\\${NODE_FILE}"
guest "apt-get clean && rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*"

guest "mkdir -p /workspace /opt/pocketdev /root/.gradle/init.d; printf 'ubuntu-20.04.5-arm64\\n' > /.pocket-rootfs-version; printf 'core-bundle-$VERSION\\n' > /.pocket-core-tools-version; printf 'core-bundle-$VERSION\\n' > /.pocket-runtime-ready; printf 'ubuntu-maintenance-v1\\n' > /.pocket-system-upgrade-version; printf '{\\\"WEB\\\":true,\\\"PYTHON\\\":false,\\\"CPP\\\":false,\\\"PHP\\\":false,\\\"ANDROID\\\":false}\\n' > /.pocket-dev-stacks.json"
guest "rm -rf /root/.cache /root/.npm /root/.composer /root/.gradle/caches /root/.ssh; find /var/log -type f -delete; rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id /root/.bash_history"

adb_cmd shell "su -c 'umount $REMOTE/rootfs/sys; umount $REMOTE/rootfs/proc; umount $REMOTE/rootfs/dev; mkdir -p $REMOTE/rootfs/output; mount --bind $REMOTE/output $REMOTE/rootfs/output'"
guest "export ZSTD_CLEVEL=19 ZSTD_NBTHREADS=0; tar --exclude='._*' --exclude='.DS_Store' --sort=name --mtime=2026-09-05T00:00:00Z --owner=0 --group=0 --numeric-owner --use-compress-program=/usr/bin/zstd -cf /output/pocketdev-core-arm64-$VERSION.tar.zst -C / ."
adb_cmd shell "su -c 'sha256sum $REMOTE/output/pocketdev-core-arm64-$VERSION.tar.zst; ls -lh $REMOTE/output/pocketdev-core-arm64-$VERSION.tar.zst'"
echo "Core bundle created on the rooted ARM64 device at $REMOTE/output/pocketdev-core-arm64-$VERSION.tar.zst"
