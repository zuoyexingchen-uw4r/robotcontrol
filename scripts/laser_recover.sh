#!/usr/bin/env bash
# =============================================================================
# 狼牙视觉 - 设备远程恢复脚本（PC 端，通过 adb 触达手机 shell 层）
#
# 用途：极端工况（应用被挤到后台/被系统回收/USB 授权弹窗卡死/ANR）下，
#       无需碰设备，在电脑上跑本脚本即可远程修正并拉起链路。
#
# 三保险配合：
#   1) 无障碍服务（AutoRecoverAccessibilityService）自动点弹窗、拉回前台
#   2) 前台服务（LaserBridgeService，START_STICKY）被回收后自动重启
#   3) 本脚本（adb 触发的 shell 层强制修正）——最后兜底
#
# 前置：手机已开 USB 调试并授权本电脑（adb devices 能看到设备）。
# 用法：
#   bash laser_recover.sh            # 自动恢复（推荐）
#   bash laser_recover.sh --force    # 强杀重开 + 清弹窗
#   bash laser_recover.sh --grant    # 一次性授予无障碍/USB/电池优化等权限
# =============================================================================
set -euo pipefail

PKG="com.robomaster.control"
ACTION_RECOVER="com.robomaster.control.action.RECOVER"

adb wait-for-device

log() { echo -e "\033[1;32m[recover]\033[0m $*"; }

# ── 1. 触发无障碍服务的恢复广播 ──
log "触发恢复广播…"
adb shell am broadcast -a "$ACTION_RECOVER" -n "$PKG/.ui.AutoRecoverAccessibilityService" 2>/dev/null || \
  adb shell am broadcast -a "$ACTION_RECOVER" 2>/dev/null || true

# ── 2. 拉回主界面 ──
log "拉回主界面…"
adb shell am start -n "$PKG/.ui.MainActivity" 2>/dev/null || true

case "${1:-}" in
  --grant)
    log "授予无障碍权限…"
    # 无障碍服务需在系统设置里手动开启，或用 settings put 直接写（需 root 或可调试）
    adb shell settings put secure enabled_accessibility_services "$PKG/.ui.AutoRecoverAccessibilityService" 2>/dev/null || true
    adb shell settings put secure accessibility_enabled 1 2>/dev/null || true
    log "授予电池优化豁免…"
    adb shell dumpsys deviceidle whitelist +$PKG 2>/dev/null || true
    adb shell cmd appops set $PKG REQUEST_IGNORE_BATTERY_OPTIMIZATIONS allow 2>/dev/null || true
    log "授予通知权限…"
    adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    log "授予 USB 权限（部分设备需手动在弹窗点允许）…"
    adb shell pm grant $PKG android.permission.FOREGROUND_SERVICE 2>/dev/null || true
    ;;

  --force)
    log "强杀应用…"
    adb shell am force-stop $PKG
    sleep 1
    log "重新启动主界面…"
    adb shell am start -n "$PKG/.ui.MainActivity"
    log "重启激光桥前台服务…"
    adb shell am start-foreground-service -n "$PKG/.laser.LaserBridgeService" 2>/dev/null || \
      adb shell am startservice -n "$PKG/.laser.LaserBridgeService" 2>/dev/null || true
    ;;

  *)
    log "重启激光桥前台服务…"
    adb shell am start-foreground-service -n "$PKG/.laser.LaserBridgeService" 2>/dev/null || \
      adb shell am startservice -n "$PKG/.laser.LaserBridgeService" 2>/dev/null || true
    ;;
esac

# ── 3. 清掉可能卡死的系统对话框 ──
log "清理可能卡死的对话框（点击『确定/允许』）…"
adb shell input keyevent KEYCODE_ENTER 2>/dev/null || true

log "完成。"
