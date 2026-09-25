#!/usr/bin/env bash
set -euo pipefail

# Build the Ubuntu 24.04 Core bundle (roadmap 3i, ISSUE-008) on a rooted ARM64
# Android phone. Same contract as build-core-on-rooted-android.sh — the phone is
# only the build host, the archive is made from the official Ubuntu ARM64
# base — with three deliberate differences:
#
#   1. Base is ubuntu-base 24.04.5 (still in standard support; 20.04 is EOL
#      since May 2025 and receives no security patches — the whole point of 3i).
#   2. On-device maintenance marker is "ubuntu-maintenance-v3": the app no
#      longer runs unattended `apt-get upgrade` on devices. Security patches
#      ship inside rebuilt Core bundles like this one, so two devices on the
#      same bundle hold identical package state.
#   3. The bundle is opt-in: devices upgrade through Settings → Linux base,
#      which keeps 20.04 on disk as a rollback root until the first successful
#      task on 24.04.
#
# After this script prints its SHA-256, activation is a release-engineering
# step (do NOT skip; the CI bundle fetcher fails closed on every manifest
# entry, so publish the asset before touching the manifest):
#   1. Upload pocketdev-core24-arm64-<VERSION>.tar.zst to the runtime GitHub
#      release (the channel behind runtimeReleaseBaseUrl).
#   2. Add the "core24" entry to dist/runtime-bundles/manifest.json.
#   3. Fill UBUNTU_24_BUNDLE in app/src/main/java/com/jarves/mh/runtime/
#      RootfsMigration.kt with the digest printed below.
#   4. Run the IT-13 device matrix (Android 9–15 + rollback drill) before any
#      release makes 24.04 the default for fresh installs.
ADB_SERIAL="${ADB_SERIAL:-}"
REMOTE="${POCKETDEV_REMOTE_DIR:-/data/adb/pocketdev-bundle24}"
VERSION="${POCKETDEV_CORE24_VERSION:-2026.09.6}"
DNS_SERVER="${POCKETDEV_DNS:-1.1.1.1}"
ROOTFS_FILE="ubuntu-base-24.04.5-base-arm64.tar.gz"
ROOTFS_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/${ROOTFS_FILE}"
# Keep in sync with RootfsMigrationPolicy (app/src/main/java/com/jarves/mh/runtime/RootfsMigration.kt).
ROOTFS_MARKER="ubuntu-24.04.5-arm64"
CORE_TOOLS_MARKER="core-bundle-${VERSION}"
MAINTENANCE_MARKER="ubuntu-maintenance-v3"

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

# Patches are applied at BUILD time and frozen into the digest; devices never
# upgrade on their own (ISSUE-008). Record the frozen package list so two
# clean devices can be compared with `dpkg -l`.
guest "printf 'nameserver $DNS_SERVER\\n' > /etc/resolv.conf; apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade"
guest "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git ca-certificates curl wget unzip zip xz-utils zstd"
guest "dpkg -l | tail -n +6 | awk '{print \\$2, \\$3}' | sort > /root/.pocket-package-manifest"
guest "set -e; NODE_VERSION=v24.19.0; NODE_FILE=node-\\${NODE_VERSION}-linux-arm64.tar.gz; cd /tmp; curl -fsSLO https://nodejs.org/dist/\\${NODE_VERSION}/\\${NODE_FILE}; curl -fsSL https://nodejs.org/dist/\\${NODE_VERSION}/SHASUMS256.txt | grep \\\"  \\${NODE_FILE}\\\" | sha256sum -c -; mkdir -p /usr/local/lib/nodejs; tar -xzf \\${NODE_FILE} -C /usr/local/lib/nodejs; ln -sfn /usr/local/lib/nodejs/node-\\${NODE_VERSION} /usr/local/lib/nodejs/current; ln -sfn /usr/local/lib/nodejs/current/bin/node /usr/local/bin/node; ln -sfn /usr/local/lib/nodejs/current/bin/npm /usr/local/bin/npm; ln -sfn /usr/local/lib/nodejs/current/bin/npx /usr/local/bin/npx; rm -f /tmp/\\${NODE_FILE}"
guest "apt-get clean && rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*"

guest "mkdir -p /workspace /opt/pocketdev /root/.gradle/init.d; printf '$ROOTFS_MARKER\\n' > /.pocket-rootfs-version; printf '$CORE_TOOLS_MARKER\\n' > /.pocket-core-tools-version; printf '$CORE_TOOLS_MARKER\\n' > /.pocket-runtime-ready; printf '$MAINTENANCE_MARKER\\n' > /.pocket-system-upgrade-version; printf '{\\\"WEB\\\":true,\\\"PYTHON\\\":false,\\\"CPP\\\":false,\\\"PHP\\\":false,\\\"ANDROID\\\":false}\\n' > /.pocket-dev-stacks.json"
guest "rm -rf /root/.cache /root/.npm /root/.composer /root/.gradle/caches /root/.ssh; find /var/log -type f -delete; rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id /root/.bash_history"

adb_cmd shell "su -c 'umount $REMOTE/rootfs/sys; umount $REMOTE/rootfs/proc; umount $REMOTE/rootfs/dev; mkdir -p $REMOTE/rootfs/output; mount --bind $REMOTE/output $REMOTE/rootfs/output'"
guest "export ZSTD_CLEVEL=19 ZSTD_NBTHREADS=0; tar --exclude='._*' --exclude='.DS_Store' --sort=name --mtime=2026-09-25T00:00:00Z --owner=0 --group=0 --numeric-owner --use-compress-program=/usr/bin/zstd -cf /output/pocketdev-core24-arm64-$VERSION.tar.zst -C / ."
adb_cmd shell "su -c 'sha256sum $REMOTE/output/pocketdev-core24-arm64-$VERSION.tar.zst; ls -lh $REMOTE/output/pocketdev-core24-arm64-$VERSION.tar.zst'"

echo "Core 24.04 bundle created at $REMOTE/output/pocketdev-core24-arm64-$VERSION.tar.zst"
echo "Paste this into dist/runtime-bundles/manifest.json (only AFTER uploading the asset):"
printf '    "core24": {\n      "version": "%s",\n      "file": "pocketdev-core24-arm64-%s.tar.zst",\n      "sha256": "<digest printed above>",\n      "compressedBytes": <bytes>,\n      "uncompressedBytes": <bytes>,\n      "base": "Ubuntu 24.04.5 ARM64",\n      "includes": [\n        "Node.js 24.19.0",\n        "npm 11.17.0",\n        "Git (patched at build time)",\n        "Frozen package manifest",\n        "No coding agents",\n        "No optional language stacks"\n      ]\n    }\n' "$VERSION" "$VERSION"
