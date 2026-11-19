#!/usr/bin/env bash
# =============================================================================
# 狼牙视觉 - 安卓设备 IP 切换脚本（PC 端，通过 adb 修改手机静态 IP）
#
# 槽位表（与 app 端 RobotSlots.kt、上位机一致）：
#   槽位  机器人        robot_id   IP
#   R1    红方射击      red_r1     192.168.1.3
#   R2    红方兑矿      red_r2     192.168.1.4
#   B1    蓝方射击      blue_r1    192.168.1.5
#   B2    蓝方兑矿      blue_r2    192.168.1.6
#
# 两种用法：
#   1) 直接改： bash switch_ip.sh red_r1        # 钉静态 IP
#              bash switch_ip.sh dhcp           # 恢复默认 DHCP
#   2) 监听模式： bash switch_ip.sh --listen [port]
#      监听 UDP，收到安卓 App 发来的 "switch_ip <robot_id>" 后改 IP。
#      App 按钮 → UDP 广播 "switch_ip red_r1" → 本脚本 → adb shell ip addr ...
#      App 的「恢复DHCP」→ 广播 "switch_ip dhcp" → 本脚本摘掉静态地址、重新 DHCP
#
# 前置：手机已开 USB 调试并授权本机（adb devices 可见）。
# 改 IP 需要 root：脚本会依次尝试 `adb root` / `adb shell su -c`。
# =============================================================================
set -u

NETIF="${NETIF:-wlan0}"       # 安卓 WiFi 网卡名（部分设备是 wlan0 / wlan1，可用 ip link 查）
GATEWAY="192.168.1.1"
PREFIX="24"
LISTEN_PORT=8769

declare -A SLOTS=(
    [red_r1]="192.168.1.3"
    [red_r2]="192.168.1.4"
    [blue_r1]="192.168.1.5"
    [blue_r2]="192.168.1.6"
)

log() { echo -e "\033[1;32m[switch-ip]\033[0m $*"; }

# ── 通过 adb 在手机 shell 执行（优先 root） ──
shell_cmd() {
    # 尝试 root；失败则走普通 shell（改 IP 大概率失败，但至少能重连）
    if adb shell 'id -u' 2>/dev/null | grep -q '^0$'; then
        adb shell "$1"
    else
        adb shell "su -c '$1'" 2>/dev/null || adb shell "$1"
    fi
}

# ── 改 IP ──
set_ip() {
    local ip="$1"
    log "设置 $NETIF 静态 IP = $ip/$PREFIX …"
    shell_cmd "ip addr flush dev $NETIF"
    shell_cmd "ip addr add $ip/$PREFIX dev $NETIF"
    shell_cmd "ip link set $NETIF up"
    # 清掉可能残留的 DHCP 默认路由，再补静态网关
    shell_cmd "ip route add default via $GATEWAY dev $NETIF 2>/dev/null || ip route replace default via $GATEWAY dev $NETIF"
    log "IP 已切换为 $ip"
}

# ── 改 IP 后重连：重绑图传端口 + 重启 App 网络 ──
reconnect() {
    local pkg="com.robomaster.control"
    log "重连：重启激光桥前台服务 + 拉回主界面（图传/激光会重新绑定端口）"
    adb shell am start-foreground-service -n "$pkg/.laser.LaserBridgeService" 2>/dev/null || \
      adb shell am startservice -n "$pkg/.laser.LaserBridgeService" 2>/dev/null || true
    adb shell am start -n "$pkg/.ui.MainActivity" 2>/dev/null || true
    # 触发 App 内「重开图传 + 重连」的恢复广播
    adb shell am broadcast -a "$pkg.action.RECOVER" 2>/dev/null || true
}

do_switch() {
    local id="$1"
    local ip="${SLOTS[$id]:-}"
    if [ -z "$ip" ]; then
        echo "未知 robot_id: $id（可选：${!SLOTS[*]} 或 dhcp）" >&2
        exit 1
    fi
    set_ip "$ip"
    reconnect
}

# ── 恢复默认 DHCP ──
# App 里点「恢复DHCP」→ 广播 "switch_ip dhcp" → 这里把静态地址摘掉，交还路由器分配。
# Android 10+ 没有直接开 DHCP 客户端的公开命令，按成功率依次尝试：
#   1) dhcptool（Android 6~9 常见）；
#   2) 关/开 WiFi（触发系统重新 DHCP，最通用的软办法）；
#   3) 仍有静态地址时再 flush 一次，避免"两个地址同时存在"。
set_dhcp() {
    log "恢复默认 DHCP（$NETIF）…"
    shell_cmd "ip addr flush dev $NETIF scope global" || true
    shell_cmd "ip link set $NETIF up" || true

    if shell_cmd "command -v dhcptool" 2>/dev/null | grep -q dhcptool; then
        log "尝试 dhcptool $NETIF dhcp"
        shell_cmd "dhcptool $NETIF dhcp" || true
    else
        log "无 dhcptool，改用 WiFi 重连触发 DHCP"
        adb shell svc wifi disable 2>/dev/null || true
        sleep 2
        adb shell svc wifi enable 2>/dev/null || true
        sleep 4
    fi

    local addr
    addr=$(adb shell "ip -4 addr show dev $NETIF 2>/dev/null | grep -o 'inet [0-9.]*'" 2>/dev/null | head -1)
    if [ -n "$addr" ]; then
        log "当前地址：$addr（DHCP 已生效）"
    else
        log "尚未拿到地址：可在手机 Wi-Fi 设置里点一下网络，或再执行一次本命令"
    fi
}

do_dhcp() {
    set_dhcp
    reconnect
}

# ── 监听模式：收 App 发来的 UDP "switch_ip <robot_id>" ──
listen_mode() {
    local port="${1:-$LISTEN_PORT}"
    log "监听 UDP 0.0.0.0:$port，等待 App 的 switch_ip 指令…"
    while true; do
        local msg
        msg=$(timeout 86400 socat -u UDP-RECV:"$port",reuseaddr - 2>/dev/null || true)
        # 或用 python 兜底（无 socat 时）
        if [ -z "$msg" ] && command -v python >/dev/null 2>&1; then
            msg=$(python -c "
import socket
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
s.bind(('0.0.0.0',$port))
s.settimeout(86400)
d,_=s.recvfrom(256)
print(d.decode('utf-8','ignore').strip())
" 2>/dev/null || true)
        fi
        case "$msg" in
            switch_ip\ *) 
                local id="${msg#switch_ip }"
                if [ "$id" = "dhcp" ]; then
                    log "收到指令：恢复默认 DHCP"
                    do_dhcp
                else
                    log "收到指令：切换到 $id"
                    do_switch "$id"
                fi
                ;;
            "") sleep 1 ;;
        esac
    done
}

case "${1:-}" in
    --listen) listen_mode "${2:-$LISTEN_PORT}" ;;
    "") echo "用法: $0 <red_r1|red_r2|blue_r1|blue_r2|dhcp> | --listen [port]" >&2; exit 1 ;;
    dhcp) do_dhcp ;;
    *) do_switch "$1" ;;
esac
