#!/usr/bin/env bash
set -euo pipefail

# ProTerm: 用 Android NDK 交叉编译 proot（官方 proot-me/proot）
# 前置条件：export ANDROID_NDK_HOME
# 前置条件：talloc 静态库已在 build_tmp/talloc-2.1.14/ 下编译完成

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
[ -z "$NDK" ] && { echo "错误: 请设置 ANDROID_NDK_HOME"; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROOT_SRC="${SCRIPT_DIR}/../external/proot/src"
ASSETS_DIR="${SCRIPT_DIR}/../app/src/main/assets/proot"
TALLOC_DIR="${SCRIPT_DIR}/../build_tmp/talloc-2.1.14"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

declare -A TALLOC_MAP=(
    ["arm64-v8a"]="libtalloc_arm64.a"
    ["armeabi-v7a"]="libtalloc_armv7a.a"
    ["x86_64"]="libtalloc_x86_64.a"
)

declare -A HOST_MAP=(
    ["arm64-v8a"]="aarch64-linux-android"
    ["armeabi-v7a"]="armv7a-linux-androideabi"
    ["x86_64"]="x86_64-linux-android"
)

API=24

for arch in arm64-v8a armeabi-v7a x86_64; do
    host="${HOST_MAP[$arch]}"
    talloc_src="${TALLOC_DIR}/${TALLOC_MAP[$arch]}"
    cc="${TOOLCHAIN}/bin/${host}${API}-clang"
    strip="${TOOLCHAIN}/bin/llvm-strip"

    if [ ! -f "$talloc_src" ]; then
        echo "错误: $talloc_src 不存在"
        exit 1
    fi

    # 临时放一个 libtalloc.a 供 -ltalloc 查找
    cp "$talloc_src" "${TALLOC_DIR}/libtalloc.a"
    trap "rm -f ${TALLOC_DIR}/libtalloc.a" EXIT

    echo ">> 编译 proot ${arch} 使用 ${cc}"

    cd "${PROOT_SRC}"
    make clean 2>/dev/null || true

    make -j"$(nproc)" \
        CC="${cc}" \
        STRIP="${strip}" \
        CFLAGS="-static -fPIE -fPIC -O2 -I${TALLOC_DIR} -Werror=implicit-function-declaration" \
        CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I${PROOT_SRC}/../lib/uthash/include -I${TALLOC_DIR}" \
        LDFLAGS="-static -pie -L${TALLOC_DIR} -ltalloc" \
        PROOT_UNBUNDLE_LOADER='#' \
        2>&1 | grep -E "error|LD.*proot"

    if [ ! -f "proot" ]; then
        echo "错误: proot 编译失败 ${arch}"
        exit 1
    fi

    "${strip}" "proot" 2>/dev/null || true

    mkdir -p "${ASSETS_DIR}/${arch}"
    cp "proot" "${ASSETS_DIR}/${arch}/proot"
    chmod 755 "${ASSETS_DIR}/${arch}/proot"

    echo "  ✅ ${arch} 完成: $(ls -lh ${ASSETS_DIR}/${arch}/proot | awk '{print $5}')"
    rm -f "${TALLOC_DIR}/libtalloc.a"
done

echo ""
echo "全部完成！"
ls -lhR "${ASSETS_DIR}"
