ui_print "- 正在初始化 Fish for Android..."

# 设置模块内部文件的权限
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755

mkdir -p /data/adb/mihomo-cmfa

cat > "$MODPATH/service.sh" << 'EOF'
#!/system/bin/sh
MODDIR=${0%/*}
RUNDIR=/data/adb/mihomo-cmfa
BIN="$MODDIR/bin/mihomo-android"
CFG="$RUNDIR/config.yaml"
SECRET_FILE="$RUNDIR/secret"
PID="$RUNDIR/mihomo.pid"
LOG="$RUNDIR/mihomo.log"

ensure_cmfa_controller() {
  [ -f "$CFG" ] || return 1

  start_count=$(grep -c '^# ===== cmfa managed block =====$' "$CFG")
  end_count=$(grep -c '^# ===== end cmfa managed block =====$' "$CFG")
  if [ "$start_count" -ne "$end_count" ] || [ "$start_count" -gt 1 ]; then
    return 1
  fi

  secret=""
  if [ -f "$SECRET_FILE" ]; then
    secret=$(cat "$SECRET_FILE")
  fi
  secret_escaped=$(printf '%s' "$secret" | tr -d '\r\n' | sed "s/'/''/g")

  tmp="$RUNDIR/config.tmp.$$"
  awk '
    BEGIN { skip = 0 }
    /^# ===== cmfa managed block =====$/ { skip = 1; next }
    /^# ===== end cmfa managed block =====$/ { skip = 0; next }
    skip == 0 { print }
  ' "$CFG" > "$tmp" || return 1

  cat >> "$tmp" <<EOF2

# ===== cmfa managed block =====
external-controller: "127.0.0.1:16756"
external-controller-tls: ""
external-ui: ""
secret: '$secret_escaped'
# ===== end cmfa managed block =====
EOF2

  mv "$tmp" "$CFG"
}

start_mihomo() {
  if [ ! -f "$CFG" ]; then
    echo "[cmfa] config not found: $CFG" >> "$LOG"
    exit 1
  fi
  if ! ensure_cmfa_controller; then
    echo "[cmfa] failed to inject managed controller block" >> "$LOG"
    exit 1
  fi
  if [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; then
    kill "$(cat "$PID")" 2>/dev/null
    sleep 1
  fi
  nohup "$BIN" -f "$CFG" > "$LOG" 2>&1 &
  echo $! > "$PID"
}

stop_mihomo() {
  if [ -f "$PID" ]; then
    kill "$(cat "$PID")" 2>/dev/null
    rm -f "$PID"
  fi
}

reload_mihomo() {
  if [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; then
    kill -HUP "$(cat "$PID")" 2>/dev/null
  fi
}

case "$1" in
  start) start_mihomo ;;
  stop) stop_mihomo ;;
  reload) reload_mihomo ;;
  *)
    start_mihomo
    ;;
esac
EOF

set_perm "$MODPATH/service.sh" 0 0 0755

# 提示：第一次安装后建议重启，或者手动执行 service.sh
ui_print "- 安装完成！"
