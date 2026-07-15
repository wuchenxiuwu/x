#!/usr/bin/env bash
# prepare_rootfs.sh
#
# 下载 Alpine minirootfs（最小根文件系统），重打包为标准 tar.gz，
# 放入 app/src/main/assets/rootfs.tar.gz，供 App 安装时解压为"容器默认环境"。
#
# 用法：
#   bash scripts/prepare_rootfs.sh [arch] [alpine_version]
#     arch            默认 aarch64；可选 armv7 / x86_64
#     alpine_version  默认 3.20.3
#
# 例：
#   bash scripts/prepare_rootfs.sh aarch64
#   bash scripts/prepare_rootfs.sh armv7
set -euo pipefail

ARCH="${1:-aarch64}"
ALPINE_VER="${2:-3.20.3}"
OUT="$(cd "$(dirname "$0")/../app/src/main/assets" && pwd)/rootfs.tar.gz"

case "$ARCH" in
  aarch64) ROOTFS="alpine-minirootfs-${ALPINE_VER}-aarch64.tar.gz" ;;
  armv7)   ROOTFS="alpine-minirootfs-${ALPINE_VER}-armv7.tar.gz" ;;
  x86_64)  ROOTFS="alpine-minirootfs-${ALPINE_VER}-x86_64.tar.gz" ;;
  *) echo "未知 arch: $ARCH（可选 aarch64/armv7/x86_64）"; exit 1 ;;
esac

# Alpine 稳定发布路径（v3.20 系列）
BASE="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases"
URL="$BASE/$ARCH/$ROOTFS"

echo ">> 下载 $URL"
TMP="$(mktemp -d)"
curl -fL "$URL" -o "$TMP/$ROOTFS"

echo ">> 重打包为标准 tar.gz（tar 内容不变，仅压缩，权限位被保留）"
mkdir -p "$(dirname "$OUT")"
gunzip -c "$TMP/$ROOTFS" | gzip -9 -c > "$OUT"
rm -rf "$TMP"

echo "完成 -> $OUT ($(du -h "$OUT" | cut -f1))"
echo "提示：多架构请重复执行并改名，例如："
echo "  bash scripts/prepare_rootfs.sh armv7 && mv $OUT rootfs-armv7.tar.gz"
