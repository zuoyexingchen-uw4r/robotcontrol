"""warhud.gamepad — USB 游戏手柄输入（pygame/SDL 轮询；键鼠降级为后备）。

硬件：标准 USB 有线手柄（PS/XInput 布局：双摇杆 + 十字键 +
SELECT/START/ANALOG + 1-4 动作键 + 双扳机）。Windows 下 SDL 即插即用。

默认映射（不同手柄序号有差异时，在 ~/.warhud/config.json 用 gamepad_map 覆盖）：

    左摇杆 axis 0/1   → 底盘平移 mx/my（mm/s，满格 800）
    右摇杆 axis 2/3   → 云台 yaw/pitch 角速度（°/s，满格 90）
    十字键 hat 0      → 底盘数字式平移（摇杆无输入时覆盖）
    RT 扳机 btn7/轴5  → 开火（边沿，等同旧版 F 键/鼠标左键）
    LT 扳机 btn6/轴4  → 精确瞄准（云台灵敏度 ×0.35）
    A btn0            → 切换「自动开火」开关
    B btn1            → 切换追踪目标（高地靶 ↔ 基地靶）
    X btn2            → 检测框叠加显隐
    Y btn3            → 设置面板显隐（等同旧版 Tab）
    START btn9        → 比赛计时暂停/继续
    SELECT btn8       → 状态面板显隐

稳健性：
- 死区 0.12 + 指数响应曲线：中心微调不抖、满格不迟滞
- USB 热插拔：轮询异常即标记掉线，退避重连，恢复自动接回
- 无 pygame / 未插手柄：静默降级（connected=False），键鼠后备立即可用
- 首次接入未知手柄：HUD 顶栏显示手柄名，序号不对时改 gamepad_map 即可
"""

from __future__ import annotations

import collections
import math
import threading
import time
from dataclasses import dataclass

DEFAULT_MAP = {
    "move_x": 0, "move_y": 1,          # 左摇杆
    "aim_x": 2, "aim_y": 3,            # 右摇杆
    "hat": 0,                          # 十字键
    "fire": 7, "aim": 6,               # 扳机（按钮式）
    "fire_axis": 5, "aim_axis": 4,     # 扳机（部分廉价手柄是模拟轴）
    "btn_a": 0, "btn_b": 1, "btn_x": 2, "btn_y": 3,
    "btn_start": 9, "btn_select": 8,
}

DEFAULTS = {
    "gamepad_deadzone": 0.12,
    "gamepad_aim_exponent": 2.0,
    "gamepad_move_exponent": 1.6,
    "gamepad_move_max_mmps": 800,
    "gamepad_aim_max_dps": 90.0,
    "gamepad_aim_slow_scale": 0.35,
    "gamepad_poll_hz": 100,
}

# 离散按键 → 动作（边沿触发，见 MainWindow._on_pad_action）
ACTION_BUTTONS = {
    "btn_a": "toggle_autofire",
    "btn_b": "cycle_target",
    "btn_x": "toggle_overlay",
    "btn_y": "toggle_settings",
    "btn_start": "toggle_pause",
    "btn_select": "cycle_panel",
}


@dataclass
class RawPad:
    """一帧原始手柄状态（source.poll 的返回；None = 未连接）。"""
    axes: tuple = ()
    buttons: tuple = ()
    hat: tuple = (0, 0)


@dataclass
class PadState:
    """连续量快照（UI 线程读取，字段全是标量，快照即副本）。"""
    yaw_rate: float = 0.0    # °/s，右为正
    pitch_rate: float = 0.0  # °/s，上为正
    mx: int = 0              # mm/s，右为正
    my: int = 0              # mm/s，前为正
    aim_slow: bool = False   # LT 精确瞄准
    connected: bool = False


class MockSource:
    """测试源：调用方直接写 self.raw，poll() 原样返回（None=掉线）。"""

    def __init__(self, raw: RawPad | None = None):
        self.raw = raw
        self.name = "mock-pad"

    def poll(self):
        return self.raw

    def close(self):
        pass


class PygameSource:
    """真实手柄源：懒加载 pygame joystick；异常即掉线，下次 poll 重新枚举。"""

    def __init__(self):
        self._joy = None
        self.name = ""

    def _ensure(self) -> bool:
        if self._joy is not None:
            return True
        try:
            import pygame
            pygame.joystick.init()
            if pygame.joystick.get_count() <= 0:
                return False
            joy = pygame.joystick.Joystick(0)
            joy.init()
            self._joy = joy
            self.name = (joy.get_name() or "USB 手柄").strip()
            return True
        except Exception:
            self._joy = None
            return False

    def poll(self) -> RawPad | None:
        if not self._ensure():
            return None
        try:
            import pygame
            pygame.event.pump()
            j = self._joy
            axes = tuple(j.get_axis(i) for i in range(j.get_numaxes()))
            buttons = tuple(bool(j.get_button(i)) for i in range(j.get_numbuttons()))
            hat = j.get_hat(0) if j.get_numhats() else (0, 0)
            return RawPad(axes=axes, buttons=buttons, hat=hat)
        except Exception:
            self._joy = None      # 热插拔掉线：下次 poll 重新枚举
            return None

    def close(self):
        self._joy = None


class Gamepad:
    """手柄输入聚合：后台轮询线程 → 连续量快照 + 边沿动作队列。"""

    def __init__(self, cfg: dict | None = None, source=None):
        cfg = dict(cfg or {})
        self.cfg = cfg
        m = dict(DEFAULT_MAP)
        m.update(cfg.get("gamepad_map") or {})
        self.map = m
        self.dz = float(cfg.get("gamepad_deadzone", DEFAULTS["gamepad_deadzone"]))
        self.aim_exp = float(cfg.get("gamepad_aim_exponent", DEFAULTS["gamepad_aim_exponent"]))
        self.move_exp = float(cfg.get("gamepad_move_exponent", DEFAULTS["gamepad_move_exponent"]))
        self.move_max = int(cfg.get("gamepad_move_max_mmps", DEFAULTS["gamepad_move_max_mmps"]))
        self.aim_max = float(cfg.get("gamepad_aim_max_dps", DEFAULTS["gamepad_aim_max_dps"]))
        self.slow_scale = float(cfg.get("gamepad_aim_slow_scale", DEFAULTS["gamepad_aim_slow_scale"]))
        self.poll_hz = max(1, int(cfg.get("gamepad_poll_hz", DEFAULTS["gamepad_poll_hz"])))

        self.source = source if source is not None else PygameSource()
        self._actions: collections.deque = collections.deque()
        self._lock = threading.Lock()
        self._state = PadState()
        self._prev_fire = False
        self._prev_btn: dict[str, bool] = {}
        self._running = False
        self._thread: threading.Thread | None = None
        self.name = ""

    # ─────────── 生命周期 ───────────

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, name="gamepad", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._running = False
        try:
            self.source.close()
        except Exception:
            pass

    # ─────────── UI 线程接口 ───────────

    def state(self) -> PadState:
        with self._lock:
            return self._state

    def drain_actions(self) -> list[str]:
        with self._lock:
            out = list(self._actions)
            self._actions.clear()
        return out

    # ─────────── 轮询线程 ───────────

    def _loop(self) -> None:
        period = 1.0 / self.poll_hz
        misses = 0
        while self._running:
            try:
                raw = self.source.poll()
            except Exception:
                raw = None
            if raw is None:
                misses += 1
                with self._lock:
                    self._state = PadState(connected=False)
                    self._actions.clear()
                self._prev_fire = False
                self._prev_btn.clear()
                # 未插手柄 1s 试探一次；插上后异常才快速退避
                time.sleep(1.0 if misses >= 3 else 0.25)
                continue
            misses = 0
            try:
                self._process(raw)
            except Exception:
                pass
            time.sleep(period)

    # ─────────── 单帧处理 ───────────

    def _axis(self, raw: RawPad, key: str) -> float:
        idx = self.map.get(key)
        if idx is None or idx >= len(raw.axes):
            return 0.0
        v = float(raw.axes[idx])
        if abs(v) < self.dz:
            return 0.0
        return math.copysign((abs(v) - self.dz) / (1.0 - self.dz), v)

    def _pressed(self, raw: RawPad, key: str) -> bool:
        idx = self.map.get(key)
        return idx is not None and idx < len(raw.buttons) and bool(raw.buttons[idx])

    def _trigger(self, raw: RawPad, btn_key: str, axis_key: str) -> bool:
        """扳机：按钮或模拟轴（>0.5）任一命中即算按下。"""
        if self._pressed(raw, btn_key):
            return True
        idx = self.map.get(axis_key)
        if idx is not None and idx < len(raw.axes):
            return float(raw.axes[idx]) > 0.5
        return False

    def _process(self, raw: RawPad) -> None:
        # 右摇杆 → 云台角速度（右为正、上为正）
        rx = self._curve(self._axis(raw, "aim_x"), self.aim_exp)
        ry = self._curve(self._axis(raw, "aim_y"), self.aim_exp)
        aim_slow = self._trigger(raw, "aim", "aim_axis")
        scale = self.slow_scale if aim_slow else 1.0
        yaw_rate = rx * self.aim_max * scale
        pitch_rate = -ry * self.aim_max * scale

        # 左摇杆 → 底盘平移（前为正）
        lx = self._curve(self._axis(raw, "move_x"), self.move_exp)
        ly = self._curve(self._axis(raw, "move_y"), self.move_exp)
        mx = int(round(lx * self.move_max))
        my = int(round(-ly * self.move_max))
        # 十字键覆盖（数字式，满速）
        hx, hy = raw.hat
        if hx or hy:
            mx = int(hx * self.move_max)
            my = int(-hy * self.move_max)

        # 开火边沿
        fire = self._trigger(raw, "fire", "fire_axis")
        fire_edge = fire and not self._prev_fire
        self._prev_fire = fire

        # 功能键边沿
        acts: list[str] = []
        if fire_edge:
            acts.append("fire")
        for key, kind in ACTION_BUTTONS.items():
            down = self._pressed(raw, key)
            if down and not self._prev_btn.get(key, False):
                acts.append(kind)
            self._prev_btn[key] = down

        with self._lock:
            self._state = PadState(yaw_rate=yaw_rate, pitch_rate=pitch_rate,
                                   mx=mx, my=my, aim_slow=aim_slow,
                                   connected=True)
            self._actions.extend(acts)
            self.name = self.source.name or "USB 手柄"

    @staticmethod
    def _curve(v: float, exp: float) -> float:
        if v == 0.0:
            return 0.0
        return math.copysign(abs(v) ** exp, v)


# ═══════════════════════ 自测（MockSource，无需手柄/pygame） ═══════════════════════

if __name__ == "__main__":
    ok = 0
    total = 0

    def check(name, cond, detail=""):
        global ok, total
        total += 1
        print(("  ✓ " if cond else "  ✗ ") + name + ("" if cond else f" {detail}"))
        if cond:
            ok += 1

    def one_frame(g: Gamepad) -> bool:
        """手动跑一帧处理（不启线程，测试确定性）；掉线帧返回 False。"""
        raw = g.source.poll()
        if raw is None:
            return False
        g._process(raw)
        return True

    def pad(axes=(), buttons=(), hat=(0, 0)):
        return Gamepad(cfg={"gamepad_poll_hz": 1000},
                       source=MockSource(RawPad(axes, buttons, hat)))

    print("warhud.gamepad 自测（MockSource）")

    # 死区
    g = pad(axes=(0.05, 0, 0, 0), buttons=(False,) * 10)
    one_frame(g)
    check("死区内输出 0", g.state().mx == 0 and g.state().yaw_rate == 0.0)

    # 左摇杆满格 → 底盘满速（摇杆上推 my 为正）
    g = pad(axes=(0, -1, 0, 0), buttons=(False,) * 10)
    one_frame(g)
    st = g.state()
    check("左摇杆满格前移", st.my == 800 and st.mx == 0, f"mx={st.mx} my={st.my}")

    # 右摇杆满格 → 云台角速度满格（右/上为正）
    g = pad(axes=(0, 0, 1, -1), buttons=(False,) * 10)
    one_frame(g)
    st = g.state()
    check("右摇杆满格角速度", abs(st.yaw_rate - 90.0) < 1e-6
          and abs(st.pitch_rate - 90.0) < 1e-6, f"yaw={st.yaw_rate} pitch={st.pitch_rate}")

    # 响应曲线单调（死区外）
    g = pad(axes=(0.5, 0, 0, 0), buttons=(False,) * 10)
    one_frame(g)
    half = g.state().mx
    g.source.raw = RawPad((0.8, 0, 0, 0), (False,) * 10, (0, 0))
    one_frame(g)
    check("曲线单调递增", 0 < half < g.state().mx < 800, f"{half} vs {g.state().mx}")

    # 十字键覆盖左摇杆
    g = pad(axes=(0, 0, 0, 0), buttons=(False,) * 10, hat=(-1, 0))
    one_frame(g)
    st = g.state()
    check("十字键覆盖", st.mx == -800 and st.my == 0, f"mx={st.mx}")

    # LT 精确瞄准减速
    g = pad(axes=(0, 0, 1, 0),
            buttons=(False, False, False, False, False, False, True, False))
    one_frame(g)
    st = g.state()
    check("LT 减速 ×0.35", abs(st.yaw_rate - 31.5) < 1e-6 and st.aim_slow,
          f"yaw={st.yaw_rate}")

    # 扳机轴方案（按钮全 False，axis5=0.9 开火）
    g = pad(axes=(0, 0, 0, 0, 0, 0.9), buttons=(False,) * 10)
    one_frame(g)
    check("扳机轴触发开火动作", "fire" in g.drain_actions())

    # 开火边沿：长按只发一次
    g = pad(axes=(), buttons=(False,) * 7 + (True,) + (False,) * 2)
    one_frame(g)
    first = g.drain_actions()
    one_frame(g)
    second = g.drain_actions()
    check("开火边沿单发", first == ["fire"] and second == [], f"{first} / {second}")

    # 功能键边沿（A B X Y START SELECT 全按）
    g = pad(axes=(), buttons=(True,) * 10)
    one_frame(g)
    acts = g.drain_actions()
    for kind in ("toggle_autofire", "cycle_target", "toggle_overlay",
                 "toggle_settings", "toggle_pause", "cycle_panel"):
        check(f"动作 {kind}", kind in acts)
    one_frame(g)
    check("功能键不重复触发", g.drain_actions() == [])

    # 掉线 → poll 返回 None，轮询线程标记掉线并跳过处理；恢复后接回
    src = MockSource(RawPad((1, 0, 0, 0), (False,) * 10, (0, 0)))
    g = Gamepad(cfg={"gamepad_poll_hz": 1000}, source=src)
    one_frame(g)
    check("连接状态 True", g.state().connected)
    src.raw = None
    check("掉线帧跳过处理", one_frame(g) is False)
    src.raw = RawPad((0, 0, 1, 0), (False,) * 10, (0, 0))
    one_frame(g)
    check("恢复连接", g.state().connected and abs(g.state().yaw_rate - 90.0) < 1e-6)

    # gamepad_map 覆盖（自定义轴序；注意成对覆盖，如 aim_x 与 aim_axis 别撞号）
    g = Gamepad(cfg={"gamepad_map": {"aim_x": 4, "aim_y": 5, "aim_axis": 99}},
                source=MockSource(RawPad((0, 0, 0, 0, 1, 0), (False,) * 10, (0, 0))))
    one_frame(g)
    check("映射覆盖生效", abs(g.state().yaw_rate - 90.0) < 1e-6)

    print(f"\n{ok}/{total} 通过" + (" ✅" if ok == total else " ❌"))
    raise SystemExit(0 if ok == total else 1)
