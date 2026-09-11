#!/usr/bin/env bash
# Start the protected service on a rootable development emulator.
set -euo pipefail
SERIAL="${SERIAL:-emulator-5554}"
SIGNAL_PORT="${SIGNAL_PORT:-8080}"
VIDEO_CODEC="${VIDEO_CODEC:-h264}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ "$SIGNAL_PORT" =~ ^[0-9]{1,5}$ ]] && (( 10#$SIGNAL_PORT > 0 && 10#$SIGNAL_PORT <= 65535 )) || {
  echo "无效 SIGNAL_PORT" >&2; exit 1;
}
[[ "$VIDEO_CODEC" =~ ^(h264|h265|av1)$ ]] || { echo "无效 VIDEO_CODEC" >&2; exit 1; }

# Reboot resets adbd's uid. Re-establish root before starting the protected service.
adb -s "$SERIAL" root >/dev/null
adb -s "$SERIAL" wait-for-device
uid="$(adb -s "$SERIAL" shell id -u | tr -d '\r\n')"
[ "$uid" = 0 ] || { echo "需要支持 adb root 的开发模拟器；当前 UID=$uid" >&2; exit 1; }

if [ -z "${SIGNAL_TOKEN:-}" ]; then
  SIGNAL_TOKEN="$(openssl rand -hex 32)"
fi
# Avoid bounded ERE repeats: macOS regex implementations may cap them at 255.
token_length=${#SIGNAL_TOKEN}
if (( token_length < 32 || token_length > 256 )); then
  echo "SIGNAL_TOKEN 长度必须为 32–256（当前 $token_length）" >&2; exit 1;
fi
case "$SIGNAL_TOKEN" in
  *[!a-zA-Z0-9_-]*)
    echo "SIGNAL_TOKEN 只能包含字母、数字、下划线或连字符" >&2; exit 1 ;;
esac
args="4.1,signal_port=$SIGNAL_PORT,video_codec=$VIDEO_CODEC,signal_token=$SIGNAL_TOKEN"
# am --esa uses comma-separated values; disallow commas/newlines in these options.
for key in TURN_URL TURN_USER TURN_PASSWORD; do
  value="${!key:-}"
  [[ "$value" != *","* && "$value" != *$'\n'* && "$value" != *$'\r'* ]] || {
    echo "$key 不能包含逗号或换行" >&2; exit 1;
  }
done
if [ -n "${TURN_URL:-}" ]; then
  args="$args,turn_url=$TURN_URL,turn_user=${TURN_USER:-},turn_password=${TURN_PASSWORD:-}"
fi
# Quote for the remote shell as well as the local shell.
quoted_args="'${args//\'/\'\\\'\'}'"
result="$(adb -s "$SERIAL" shell "am startservice -n com.genymobile.scrcpy/.ServerService --esa args $quoted_args" 2>&1)" || {
  echo "启动服务失败，请检查 adb logcat -s scrcpy:*" >&2; exit 1;
}
# am can print Error while returning exit code 0. Never echo the intent (contains token).
if [[ "$result" == *"Error:"* || "$result" == *"Exception"* ]]; then
  echo "启动服务被拒绝，请检查权限和 adb logcat -s scrcpy:*" >&2; exit 1;
fi

ready=0
for ((attempt=0; attempt<15; attempt++)); do
  if adb -s "$SERIAL" shell "ss -ltn" 2>/dev/null |
      awk -v port="$SIGNAL_PORT" '$1 == "LISTEN" && $4 ~ (":" port "$") { found=1 } END { exit !found }'; then
    ready=1
    break
  fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "$SIGNAL_PORT 端口未监听，请检查 adb logcat -s scrcpy:*" >&2; exit 1; }
adb -s "$SERIAL" forward "tcp:$SIGNAL_PORT" "tcp:$SIGNAL_PORT" >/dev/null
mkdir -p "$ROOT/tmp"
(umask 077; printf '%s\n' "$SIGNAL_TOKEN" > "$ROOT/tmp/session-token.txt")
chmod 600 "$ROOT/tmp/session-token.txt"
echo "云机就绪：127.0.0.1:$SIGNAL_PORT"
echo "会话凭证已写入 $ROOT/tmp/session-token.txt，请填入网页会话凭证框（5 分钟内连接，单次使用）。"
