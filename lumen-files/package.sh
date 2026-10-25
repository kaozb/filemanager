#!/usr/bin/env bash
# 一键打包：清理缓存 → Debug 构建 → 对齐 → 签名 → 验证 → 清理中间文件
# 用法：./package.sh
set -euo pipefail

cd "$(dirname "$0")"

# ------------------------------------------------------------------ 环境探测
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  SDK=/opt/android-sdk
fi
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$BUILD_TOOLS" ]; then
  echo "找不到 Android build-tools，请检查 SDK 路径：$SDK" >&2
  exit 1
fi

ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KS=keystore/mibo.jks
APK_SRC=app/build/outputs/apk/debug/app-debug.apk
APK_OUT=LumenFiles.apk

# ---------------------------------------------------- 0. 清理缓存（避免旧产物混入）
echo "==> 清理缓存"
gradle clean -q 2>/dev/null || true
rm -rf app/build build .gradle
rm -f aligned.apk "$APK_OUT" "$APK_OUT.idsig"

# ------------------------------------------------------------ 1. Debug 构建
echo "==> 构建"
gradle assembleDebug --console=plain

# ---------------------------------------------------------------- 2. 对齐
echo "==> 对齐"
"$ZIPALIGN" -f 4 "$APK_SRC" aligned.apk

# ---------------------------------------------------------------- 3. 签名
echo "==> 签名"
"$APKSIGNER" sign --ks "$KS" --ks-key-alias mibo \
  --ks-pass pass:mibo --key-pass pass:mibo \
  --out "$APK_OUT" aligned.apk

# ---------------------------------------------------------------- 4. 验证
echo "==> 验证"
"$APKSIGNER" verify "$APK_OUT"

# ------------------------------------------------------ 5. 清理中间文件
rm -f aligned.apk "$APK_OUT.idsig"

echo "==> 完成：$APK_OUT ($(du -h "$APK_OUT" | cut -f1))"
