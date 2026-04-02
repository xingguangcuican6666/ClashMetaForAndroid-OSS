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
PID="$RUNDIR/mihomo.pid"
LOG="$RUNDIR/mihomo.log"

start_mihomo() {
  if [ ! -f "$CFG" ]; then
    echo "[cmfa] config not found: $CFG" >> "$LOG"
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
