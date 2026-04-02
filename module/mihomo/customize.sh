ui_print "- 正在初始化 Fish for Android..."

# 设置模块内部文件的权限
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755

# 提示：第一次安装后建议重启，或者手动执行 service.sh
ui_print "- 安装完成！"
