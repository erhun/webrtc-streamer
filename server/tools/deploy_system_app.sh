#!/usr/bin/env bash
# Deploy a platform-signed server without deleting app data or killing it with SIGKILL.
set -euo pipefail

PACKAGE=com.genymobile.scrcpy
SYSTEM_APK=/system/priv-app/scrcpy/scrcpy.apk
SYSTEM_SO=/system/lib64/libscrcpy_native.so
SERIAL=
APK=
NATIVE=
DEVICE_MODE=cloud
DEPLOY_STRATEGY=system
BOOT_TIMEOUT=180

log() { printf '[deploy] %s\n' "$*"; }
die() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }
while [ "$#" -gt 0 ]; do
    case "$1" in
        --serial|--apk|--native|--mode|--strategy|--boot-timeout)
            [ "$#" -ge 2 ] || die "$1 缺少参数"
            case "$1" in
                --serial) SERIAL=$2 ;;
                --apk) APK=$2 ;;
                --native) NATIVE=$2 ;;
                --mode) DEVICE_MODE=$2 ;;
                --strategy) DEPLOY_STRATEGY=$2 ;;
                --boot-timeout) BOOT_TIMEOUT=$2 ;;
            esac
            shift 2 ;;
        *) die "用法: $0 --serial SERIAL --apk 平台签名APK --native SO [--mode cloud|emulator] [--strategy system|update] [--boot-timeout 秒]" ;;
    esac
done
[ -n "$SERIAL" ] && [ -f "$APK" ] && [ -f "$NATIVE" ] || die "必须提供 serial、APK 和 native 文件"
case "$DEVICE_MODE" in cloud|emulator) ;; *) die "mode 必须为 cloud 或 emulator" ;; esac
case "$DEPLOY_STRATEGY" in system|update) ;; *) die "strategy 必须为 system 或 update" ;; esac
case "$BOOT_TIMEOUT" in ''|*[!0-9]*) die "boot-timeout 必须为正整数" ;; esac
[ "$BOOT_TIMEOUT" -gt 0 ] || die "boot-timeout 必须为正整数"
command -v adb >/dev/null || die "找不到 adb"
command -v python3 >/dev/null || die "找不到 python3"
ADB=(adb -s "$SERIAL")
staged_files=()
cleanup() {
    local status=$? staged
    # macOS Bash 3.2 treats an empty array as unset under set -u.
    for staged in ${staged_files[@]+"${staged_files[@]}"}; do
        "${ADB[@]}" shell "rm -f '$staged'" >/dev/null 2>&1 || log "临时文件清理未完成: $staged" >&2
    done
    return "$status"
}
trap cleanup EXIT

# A packaged JNI library takes precedence over /system/lib64; reject stale packaging.
APK_INFO=$(python3 - "$APK" "$NATIVE" <<'PY'
import hashlib, sys, zipfile
apk, native = sys.argv[1:]
with zipfile.ZipFile(apk) as archive:
    if 'AndroidManifest.xml' not in archive.namelist():
        raise SystemExit('APK 缺少 AndroidManifest.xml')
    library = 'lib/arm64-v8a/libscrcpy_native.so'
    bundled = library in archive.namelist()
    if bundled and hashlib.sha256(archive.read(library)).digest() != hashlib.sha256(open(native, 'rb').read()).digest():
        raise SystemExit('APK 内的 JNI 库与 --native 不一致，请重新打包和签名')
print(hashlib.sha256(open(apk, 'rb').read()).hexdigest(), int(bundled))
PY
)
read -r APK_SHA BUNDLED_NATIVE <<< "$APK_INFO"
SO_SHA=$(python3 - "$NATIVE" <<'PY'
import hashlib, sys
print(hashlib.sha256(open(sys.argv[1], 'rb').read()).hexdigest())
PY
)
[ "$("${ADB[@]}" get-state | tr -d '\r')" = device ] || die "设备未连接"

has_system_identity() {
    printf '%s\n' "$1" | grep -Eq 'userId=1000([[:space:]]|$)' \
        && printf '%s\n' "$1" | grep -Eq '(pkgFlags|flags)=.*[[:space:]]SYSTEM([[:space:]]|\])' \
        && printf '%s\n' "$1" | grep -Eq 'privateFlags=.*PRIVILEGED'
}
package_dump() {
    # Do not confuse an active user package with the hidden system base in the same dump.
    "${ADB[@]}" shell dumpsys package "$PACKAGE" | sed '/^[[:space:]]*Hidden system packages:/,$d'
}
remote_sha() {
    "${ADB[@]}" shell "sha256sum '$1'" | tr -d '\r' | awk '{print $1}'
}
verify_active_apk() {
    local dump active
    dump=$(package_dump)
    has_system_identity "$dump" || die "应用未被识别为 UID 1000 的系统特权应用，请检查包扫描、签名和部署路径"
    active=$("${ADB[@]}" shell pm path "$PACKAGE" | tr -d '\r' | sed -n 's/^package://p')
    # This APK has no splits. Validate before placing a device-provided path in a shell command.
    [[ "$active" =~ ^/[A-Za-z0-9_./=+~-]+\.apk$ ]] || die "无法确定唯一 APK 路径: $active"
    [ "$(remote_sha "$active")" = "$APK_SHA" ] || die "实际加载的 APK 与本次构建不一致: $active"
    log "验证通过: UID=1000 SYSTEM PRIVILEGED，APK=$active"
}

registered=0
dump=
packages=$("${ADB[@]}" shell pm list packages "$PACKAGE" | tr -d '\r')
if printf '%s\n' "$packages" | grep -Fxq "package:$PACKAGE"; then
    registered=1
    dump=$(package_dump)
    printf '%s\n' "$dump" | grep -Eq 'userId=1000([[:space:]]|$)' \
        || die "现有同名应用不是 UID 1000；请先处理签名/sharedUserId 迁移，不会自动卸载或删除数据"
fi

if [ "$DEPLOY_STRATEGY" = update ]; then
    [ "$registered" -eq 1 ] && has_system_identity "$dump" \
        || die "免重启更新要求系统底包已被识别为 UID 1000 的特权应用；请先使用默认 system 策略"
    [ "$BUNDLED_NATIVE" -eq 1 ] || die "免重启更新要求 APK 内包含匹配的 JNI 库；请重新打包或使用默认 system 策略"
    log "更新已识别的系统应用，保留应用数据，无需整机重启"
    # Package Manager verifies signatures and replaces the app; it also terminates the old app process.
    "${ADB[@]}" install -r "$APK"
    verify_active_apk
    log "请按原启动命令启动 ServerService，核对前台服务和音频启动日志"
    exit 0
fi

log "部署系统底包到 $SERIAL，重启后验证包扫描结果"
if [ "$DEVICE_MODE" = emulator ]; then
    "${ADB[@]}" root
    "${ADB[@]}" wait-for-device
    "${ADB[@]}" remount
fi
[ "$("${ADB[@]}" shell id -u | tr -d '\r')" = 0 ] || die "写系统目录需要 adb 管理连接具备 root 权限"

if [ "$registered" -eq 1 ]; then
    # Refresh a possible /data/app override with this exact APK; never uninstall the package.
    "${ADB[@]}" install -r "$APK"
    "${ADB[@]}" shell am stopservice -n "$PACKAGE/.ServerService"
fi
"${ADB[@]}" shell "mkdir -p /system/priv-app/scrcpy"
publish_system_file() {
    local source=$1 target=$2 expected=$3 staged
    staged="$target.new.$$"
    staged_files+=("$staged")
    "${ADB[@]}" push "$source" "$staged"
    "${ADB[@]}" shell "chmod 644 '$staged' && chown 0:0 '$staged'"
    [ "$(remote_sha "$staged")" = "$expected" ] || die "推送文件校验失败: $staged"
    # Rename preserves the inode of files still mapped by an old process until reboot.
    "${ADB[@]}" shell "mv -f '$staged' '$target'"
    "${ADB[@]}" shell "if command -v restorecon >/dev/null 2>&1; then restorecon '$target'; fi"
}
publish_system_file "$APK" "$SYSTEM_APK" "$APK_SHA"
publish_system_file "$NATIVE" "$SYSTEM_SO" "$SO_SHA"
old_system_server=$("${ADB[@]}" shell pidof system_server | tr -d '\r')
old_boot_id=$("${ADB[@]}" shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
[ -n "$old_system_server" ] || die "未找到 system_server，无法核对重启完成"
[ -n "$old_boot_id" ] || die "未找到内核 boot_id，无法核对重启完成"
"${ADB[@]}" reboot

# Cloud Android restarts can keep the host kernel's boot_id; real reboots can reuse PIDs.
# Accept a change in either marker, and bound offline probes instead of waiting indefinitely.
deadline=$((SECONDS + BOOT_TIMEOUT))
while [ "$SECONDS" -lt "$deadline" ]; do
    if state=$(python3 - "$SERIAL" <<'PY'
import subprocess, sys
try:
    result = subprocess.run(['adb', '-s', sys.argv[1], 'shell',
        'pidof system_server; cat /proc/sys/kernel/random/boot_id; getprop sys.boot_completed'],
        capture_output=True, text=True, timeout=3)
    lines = result.stdout.replace('\r', '').strip().splitlines()
    if result.returncode == 0 and len(lines) == 3:
        print('|'.join(lines))
    else:
        raise SystemExit(1)
except subprocess.TimeoutExpired:
    raise SystemExit(1)
PY
    ); then
        IFS='|' read -r system_server boot_id boot_complete <<< "$state"
        if [ -n "$system_server" ] && [ -n "$boot_id" ] && [ "$boot_complete" = 1 ] \
                && { [ "$system_server" != "$old_system_server" ] || [ "$boot_id" != "$old_boot_id" ]; }; then
            verify_active_apk
            log "系统重启和包扫描完成；请按原启动命令启动 ServerService"
            exit 0
        fi
    fi
    sleep 1
done
die "等待启动超过 ${BOOT_TIMEOUT}s；文件已部署，请检查云机和隧道连接，未宣告部署成功"
