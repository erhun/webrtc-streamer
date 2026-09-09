#!/usr/bin/env bash
# p4/deploy.sh — 部署 scrcpy 云机到模拟器（一键 push + 重启 + 启动）
#
# 用法:
#   ./p4/deploy.sh                # 仅部署（产物已在 tmp/）
#   ./p4/deploy.sh --build        # 重新编译 C++ + 打包 APK + 签名 + 部署
#   ./p4/deploy.sh --turn         # 同时启动 coturn（TURN 中继）
#   SERIAL=emulator-5554 ./p4/deploy.sh   # 指定模拟器设备
set -euo pipefail

# ============================================================
# 配置
# ============================================================
SERIAL="${SERIAL:-emulator-5554}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$ROOT/tmp"
SIGNAL_PORT="${SIGNAL_PORT:-8080}"
VIDEO_CODEC="${VIDEO_CODEC:-h264}"

CLANG="/Users/gorilla/test/webrtc/clang-llvmorg-24-init-3796-g20e97c4b-2/bin/clang++"
CLANG_LD="/Users/gorilla/test/webrtc/clang-llvmorg-24-init-3796-g20e97c4b-2/bin/ld.lld"
NDK="/Users/gorilla/Library/Android/sdk/ndk/28.2.13676358"
NDK_SYSROOT="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot"
APKSIGNER="$(find "$HOME/Library/Android/sdk/build-tools" -name apksigner 2>/dev/null | sort | tail -1)"
ZIPALIGN="$(find "$HOME/Library/Android/sdk/build-tools" -name zipalign 2>/dev/null | sort | tail -1)"
COTURN="/opt/homebrew/opt/coturn/bin/turnserver"

DO_BUILD=0
DO_TURN=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) DO_BUILD=1 ;;
    --turn) DO_TURN=1 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
  shift
done

log() { echo -e "\033[1;32m==>\033[0m $*"; }
err() { echo -e "\033[1;31m错误:\033[0m $*" >&2; }

# ============================================================
# 0. 工具链检查
# ============================================================
check_cmd() {
  command -v "$1" >/dev/null 2>&1 || { err "缺少命令: $1"; exit 1; }
}
check_cmd adb

# ============================================================
# 1. 可选：重新构建
# ============================================================
if [ "$DO_BUILD" -eq 1 ]; then
  log "重新构建 C++（clang 24 + relative vtable）"
  [ -x "$CLANG" ] || { err "clang 24 不存在: $CLANG"; exit 1; }
  [ -d "$ROOT/webrtc_materials/static_libs/obj" ] || { err "libwebrtc 产物缺失"; exit 1; }

  mkdir -p "$TMP/native" "$TMP/apk"

  # 确保 builtins + libunwind symlink
  CLANG_LIB="/Users/gorilla/test/webrtc/clang-llvmorg-24-init-3796-g20e97c4b-2/lib/clang/24/lib/aarch64-unknown-linux-android23"
  RTLIB="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/lib/clang/19/lib/linux"
  mkdir -p "$CLANG_LIB"
  ln -sf "$RTLIB/libclang_rt.builtins-aarch64-android.a" "$CLANG_LIB/libclang_rt.builtins.a"
  ln -sf "$RTLIB/aarch64/libunwind.a" "$CLANG_LIB/libunwind.a"

  # 编译 6 个 .cc
  for f in scrcpy_passthrough_encoder scrcpy_video_encoder_factory \
           scrcpy_opus_audio_encoder scrcpy_audio_encoder_factory \
           scrcpy_peer_connection jni_bridge; do
    "$CLANG" --target=aarch64-linux-android23 --sysroot="$NDK_SYSROOT" \
      -std=c++20 -fno-rtti -fno-exceptions -fPIC -nostdinc++ -DNDEBUG \
      -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_NONE \
      -DWEBRTC_POSIX -DWEBRTC_ANDROID -DWEBRTC_LINUX -DWEBRTC_ARCH_ARM64 \
      -fexperimental-relative-c++-abi-vtables \
      -I "$ROOT/webrtc_materials/third_party/libc++/src/include" \
      -I "$ROOT/webrtc_materials/buildtools/third_party/libc++" \
      -I "$ROOT/webrtc_materials/include" \
      -I "$ROOT/webrtc_materials/include/third_party/abseil-cpp" \
      -I "$ROOT/server/src/main/cpp" \
      -Wno-nullability-completeness \
      -c "$ROOT/server/src/main/cpp/$f.cc" -o "$TMP/native/$f.o"
  done

  # 链接 libscrcpy_native.so
  SL="$ROOT/webrtc_materials/static_libs/obj"
  "$CLANG" --target=aarch64-linux-android23 --sysroot="$NDK_SYSROOT" \
    -shared -fuse-ld="$CLANG_LD" -nostdlib++ \
    -Wl,--start-group \
    "$TMP/native/scrcpy_passthrough_encoder.o" "$TMP/native/scrcpy_video_encoder_factory.o" \
    "$TMP/native/scrcpy_opus_audio_encoder.o" "$TMP/native/scrcpy_audio_encoder_factory.o" \
    "$TMP/native/scrcpy_peer_connection.o" "$TMP/native/jni_bridge.o" \
    "$SL/libwebrtc.a" $(find "$SL/third_party" -name '*.a') $(find "$SL/buildtools" -name '*.a') \
    -Wl,--end-group \
    -o "$TMP/native/libscrcpy_native.so" -llog -ldl -lm -lz

  # 验证未定义符号
  NM="$(find "$NDK/toolchains/llvm/prebuilt" -name llvm-nm | head -1)"
  UNDEF_COUNT="$("$NM" -D --undefined-only "$TMP/native/libscrcpy_native.so" 2>/dev/null | grep -c webrtc || true)"
  [ "$UNDEF_COUNT" -eq 0 ] || { err "链接存在 $UNDEF_COUNT 个未定义 webrtc 符号"; exit 1; }
  log "C++ 链接成功（未定义 webrtc 符号 = 0）"

  # gradle 打包 + platform 签名
  log "gradle 打包 + platform 签名"
  (cd "$ROOT" && ./gradlew -p server assembleDebug -q)
  "$ZIPALIGN" -f 4 "$ROOT/server/build/outputs/apk/debug/server-debug.apk" "$TMP/apk/scrcpy-aligned.apk"
  "$APKSIGNER" sign \
    --key "$TMP/aosp_keys/platform.pk8" \
    --cert "$TMP/aosp_keys/platform.x509.pem" \
    --out "$TMP/apk/scrcpy-platform.apk" "$TMP/apk/scrcpy-aligned.apk"
  log "APK 签名完成"
fi

# ============================================================
# 2. 产物检查
# ============================================================
[ -f "$TMP/apk/scrcpy-platform.apk" ] || { err "缺 $TMP/apk/scrcpy-platform.apk（先 --build）"; exit 1; }
[ -f "$TMP/native/libscrcpy_native.so" ] || { err "缺 $TMP/native/libscrcpy_native.so（先 --build）"; exit 1; }

# ============================================================
# 3. 部署到 /system
# ============================================================
log "部署到模拟器 $SERIAL"
adb -s "$SERIAL" root >/dev/null 2>&1 || true
sleep 2
adb -s "$SERIAL" remount >/dev/null
adb -s "$SERIAL" shell "mkdir -p /system/priv-app/scrcpy"
adb -s "$SERIAL" push "$TMP/apk/scrcpy-platform.apk" /system/priv-app/scrcpy/scrcpy.apk
adb -s "$SERIAL" push "$TMP/native/libscrcpy_native.so" /system/lib64/libscrcpy_native.so

log "重启模拟器"
adb -s "$SERIAL" reboot
adb -s "$SERIAL" wait-for-device
for _ in $(seq 1 30); do
  boot="$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [ "$boot" = "1" ] && break
  sleep 3
done
sleep 3

# ============================================================
# 4. 启动云机服务
# ============================================================
log "启动云机 ServerService"
adb -s "$SERIAL" shell "am startservice -n com.genymobile.scrcpy/.ServerService --esa args '4.1,signal_port=$SIGNAL_PORT,video_codec=$VIDEO_CODEC'" || true
sleep 8

# ============================================================
# 5. adb forward + 验证
# ============================================================
adb -s "$SERIAL" forward "tcp:$SIGNAL_PORT" "tcp:$SIGNAL_PORT"
LISTENING="$(adb -s "$SERIAL" shell "ss -tlnp 2>/dev/null | grep $SIGNAL_PORT" || true)"
if [ -n "$LISTENING" ]; then
  log "✅ 云机就绪: 监听 $SIGNAL_PORT 端口"
else
  err "8080 端口未监听，云机启动可能失败，检查: adb logcat -s scrcpy:*"
fi

# ============================================================
# 6. 可选：启动 coturn
# ============================================================
if [ "$DO_TURN" -eq 1 ]; then
  if [ -x "$COTURN" ] && [ -f "$TMP/turnserver.conf" ]; then
    pkill -f turnserver 2>/dev/null || true
    sleep 1
    "$COTURN" -c "$TMP/turnserver.conf" >/dev/null 2>&1 &
    sleep 2
    log "✅ coturn 已启动（UDP/TCP 3478）"
  else
    err "coturn 或 tmp/turnserver.conf 缺失"
  fi
fi

log "完成。浏览器访问 H5 客户端: http://localhost:5173/（需 vite dev，见 p4/web）"
