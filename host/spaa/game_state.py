"""spaa.game_state — 赛事服务器状态（MQTT/Protobuf）与提示事件。

协议见《校内赛通信协议》：服务器 192.168.1.2:3333，MQTT + Protobuf v3，
topic 即指令名，1 Hz。本模块只做**状态缓存 + 差分事件**，不碰网络：

    GameState.apply(topic, fields) -> [Event]

网络层（paho-mqtt + protoc 生成的类）以后接进来时，只需把反序列化后的
字段 dict 丢给 apply()，界面不用改。没有服务器时用 MockSource 产生演示数据。

差分为什么在这里做：TargetStatus 只给位图，不给"刚刚哪片被打了"。
只有保存上一秒的掩码、逐位对比，才能弹出"命中 高地靶 3 号 +10"。
"""
from __future__ import annotations

import random
import time
from dataclasses import dataclass, field

STAGE_NAMES = {
    0: "未知", 1: "未开始", 2: "准备阶段", 3: "队伍暂停", 4: "清场倒计时",
    5: "比赛进行中", 6: "技术暂停", 7: "待裁判确认", 8: "本局结束", 9: "本局终止",
}

# 靶片分值（协议 §4.2）：高地 bit0/1=5，bit2/3=10；基地 bit0..2=15，bit3=45
HIGHLAND_POINTS = [5, 5, 10, 10]
BASE_POINTS = [15, 15, 15, 45]

SIDE_CN = {"red": "红方", "blue": "蓝方"}


@dataclass
class Event:
    """一条要弹 toast 的事件。"""
    kind: str                  # hit | penalty | card | stage | result
    text: str
    detail: str = ""
    tone: str = "info"         # info | good | warn | bad


@dataclass
class GameState:
    round_no: int = 0
    red_score: int = 0
    blue_score: int = 0
    stage: int = 0
    remaining_ms: int = 0
    stage_t: float = 0.0            # 收到该状态的时间，用于本地倒计时

    red_high: int = 0
    blue_high: int = 0
    red_base: int = 0
    blue_base: int = 0

    robots: dict = field(default_factory=dict)     # robot_id -> dict
    red_exchange: int = 0
    blue_exchange: int = 0
    red_claimed: int = 0
    blue_claimed: int = 0
    result: dict | None = None

    _prev_high: dict = field(default_factory=lambda: {"red": 0, "blue": 0})
    _prev_base: dict = field(default_factory=lambda: {"red": 0, "blue": 0})
    _prev_penalty: dict = field(default_factory=dict)
    _prev_cards: dict = field(default_factory=dict)
    _prev_stage: int = -1

    # ─────────── 倒计时 ───────────

    def remaining_now_ms(self) -> int:
        """本地插值：服务器 1 Hz，界面按收到时刻回推，避免秒针跳格。"""
        if not self.stage_t:
            return self.remaining_ms
        if self.stage not in (2, 3, 4, 5, 6):
            return self.remaining_ms
        el = int((time.time() - self.stage_t) * 1000)
        return max(0, self.remaining_ms - el)

    def stage_text(self) -> str:
        return STAGE_NAMES.get(self.stage, "未知")

    # ─────────── 状态更新 ───────────

    def apply(self, topic: str, f: dict) -> list[Event]:
        if topic == "GameStatus":
            return self._apply_game(f)
        if topic == "TargetStatus":
            return self._apply_target(f)
        if topic == "RobotStatus":
            return self._apply_robot(f)
        if topic == "ExchangeStatus":
            self.red_exchange = int(f.get("red_exchange_count", 0))
            self.blue_exchange = int(f.get("blue_exchange_count", 0))
            self.red_claimed = int(f.get("red_claimed_count", 0))
            self.blue_claimed = int(f.get("blue_claimed_count", 0))
            return []
        if topic == "GameResult":
            self.result = dict(f)
            if f.get("winner"):
                who = "红方" if int(f["winner"]) == 1 else "蓝方"
                return [Event("result", f"本局结束 · {who}胜", "", "good")]
            return []
        return []

    def _apply_game(self, f: dict) -> list[Event]:
        out = []
        stage = int(f.get("current_stage", self.stage))
        if stage != self._prev_stage:
            self._prev_stage = stage
            out.append(Event("stage", f"阶段：{STAGE_NAMES.get(stage, '未知')}", "", "info"))
        self.round_no = int(f.get("current_round", self.round_no))
        self.red_score = int(f.get("red_score", self.red_score))
        self.blue_score = int(f.get("blue_score", self.blue_score))
        self.stage = stage
        self.remaining_ms = int(f.get("stage_remaining_ms", self.remaining_ms))
        self.stage_t = time.time()
        return out

    def _apply_target(self, f: dict) -> list[Event]:
        out = []
        for side, key, attr, points, label in (
            ("red", "red_highland_hit_mask", "red_high", HIGHLAND_POINTS, "高地靶"),
            ("blue", "blue_highland_hit_mask", "blue_high", HIGHLAND_POINTS, "高地靶"),
            ("red", "red_base_destroyed_mask", "red_base", BASE_POINTS, "基地靶"),
            ("blue", "blue_base_destroyed_mask", "blue_base", BASE_POINTS, "基地靶"),
        ):
            new = int(f.get(key, getattr(self, attr)))
            prev = self._prev_high[side] if "high" in attr else self._prev_base[side]
            added = new & ~prev
            for bit in range(4):
                if added & (1 << bit):
                    pts = points[bit]
                    out.append(Event(
                        "hit",
                        f"命中 {SIDE_CN[side]}{label} {bit + 1} 号",
                        f"+{pts}",
                        "good" if side == "red" else "warn",
                    ))
            if "high" in attr:
                self._prev_high[side] = new
            else:
                self._prev_base[side] = new
            setattr(self, attr, new)
        return out

    def _apply_robot(self, f: dict) -> list[Event]:
        out = []
        for r in (f.get("robots") or []):
            rid = r.get("robot_id", "")
            if not rid:
                continue
            self.robots[rid] = dict(r)
            pen = int(r.get("penalty_remaining_ms", 0))
            old_pen = self._prev_penalty.get(rid, 0)
            if pen > old_pen + 500:
                out.append(Event("penalty", f"{rid} 罚时", f"-{pen / 1000:.0f}s", "warn"))
            self._prev_penalty[rid] = pen

            cards = (int(r.get("red_card_count", 0)), int(r.get("yellow_card_count", 0)))
            old = self._prev_cards.get(rid, (0, 0))
            if cards[0] > old[0]:
                out.append(Event("card", f"{rid} 红牌", f"×{cards[0]}", "bad"))
            elif cards[1] > old[1]:
                out.append(Event("card", f"{rid} 黄牌", f"×{cards[1]}", "warn"))
            self._prev_cards[rid] = cards
        return out

    # ─────────── 顶栏取值 ───────────

    def high_remaining(self, side: str) -> int:
        mask = self.red_high if side == "red" else self.blue_high
        return 4 - bin(mask & 0xF).count("1")

    def base_remaining(self, side: str) -> int:
        mask = self.red_base if side == "red" else self.blue_base
        return 4 - bin(mask & 0xF).count("1")

    def base_unlocked(self, side: str) -> bool:
        """协议：同方高地掩码为 15 时，该方基地激活。"""
        mask = self.red_high if side == "red" else self.blue_high
        return (mask & 0xF) == 0xF


class MockSource:
    """没有赛事服务器时的演示源：随机命中/罚时，用来验证界面。"""

    def __init__(self, state: GameState):
        self.s = state
        self.t0 = time.time()
        self._next = 0.0
        self.s.apply("GameStatus", {"current_round": 1, "red_score": 45, "blue_score": 30,
                                    "current_stage": 5, "stage_remaining_ms": 222000})

    def poll(self) -> list[Event]:
        now = time.time()
        if now < self._next:
            return []
        self._next = now + random.uniform(4.0, 9.0)
        r = random.random()
        if r < 0.6:
            side = random.choice(["red", "blue"])
            bit = random.randrange(4)
            key = f"{side}_highland_hit_mask"
            cur = self.s.red_high if side == "red" else self.s.blue_high
            if cur & (1 << bit):
                return []
            return self.s.apply("TargetStatus", {
                "red_highland_hit_mask": self.s.red_high,
                "blue_highland_hit_mask": self.s.blue_high,
                f"{key}": cur | (1 << bit),
            })
        if r < 0.85:
            return self.s.apply("RobotStatus", {"robots": [{
                "robot_id": "red_r2", "eligible": True,
                "penalty_remaining_ms": 30000,
                "red_card_count": 0, "yellow_card_count": 0,
            }]})
        return []
