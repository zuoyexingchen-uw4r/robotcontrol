// =============================================================================
// 狼牙视觉 - ESP32 激光雷达 + 主控板混合串口桥（参考代码，Arduino 框架）
//
// 链路：雷达(串口A) ──> ESP32 ──UART(CH343/USB-CDC)──> 手机USB-host ──> 上位机
//       主控板(自身) ──> ESP32 同一根 UART 混合发送
//
// 协议（LASER_PROTOCOL.md v2，两类帧均定长 60 字节，靠帧头区分）：
//   雷达点云帧：55 AA ...（60 字节，原样透传，ESP32 不解析）
//   主控帧：    AA 55 | type | seq(2 LE) | payload(52 = 26×int16 LE) | crc16(2 LE)
//      type 0x01 = 上行(遥测)  0x02 = 下行(指令)
//
// 说明：这是「电控参考」，字段名与语义为占位/建议，具体结构需与上位机商讨后定。
//       ESP32 只做「雷达帧透传 + 主控帧组帧/解析」，不解析雷达点云内容。
// =============================================================================

#include <Arduino.h>

// ── 串口定义 ──
#define LIDAR_SERIAL    Serial1   // 接雷达（TX/RX），波特率 230400
#define HOST_SERIAL     Serial    // 接 CH343（USB-CDC），波特率 ≥ 230400
#define HOST_BAUD       460800    // CH343 上限高，取 460800 减延迟；雷达必须 230400

// ── 协议常量 ──
#define FRAME_LEN       60
#define PAYLOAD_LEN     52
#define FIELD_COUNT     26
#define MASTER_HDR0     0xAA
#define MASTER_HDR1     0x55
#define MASTER_UP       0x01
#define MASTER_DOWN     0x02

// ── 全局 ──
static uint16_t g_seq = 0;                 // 上行帧序号
static uint8_t  lidar_buf[FRAME_LEN];      // 雷达帧接收缓冲
static int      lidar_len = 0;
static uint32_t last_master_ms = 0;

// ============ CRC16-MODBUS（poly 0xA001, init 0xFFFF） ============
static uint16_t crc16_modbus(const uint8_t *p, int len) {
    uint16_t crc = 0xFFFF;
    for (int i = 0; i < len; i++) {
        crc ^= p[i];
        for (int k = 0; k < 8; k++) {
            crc = (crc & 1) ? (crc >> 1) ^ 0xA001 : crc >> 1;
        }
    }
    return crc;
}

// ============ 组一帧主控帧（60 字节） ============
// fields: 26 个 int16（不足补 0）
static void build_master(uint8_t type, uint16_t seq, const int16_t *fields) {
    uint8_t frame[FRAME_LEN] = {0};
    frame[0] = MASTER_HDR0;
    frame[1] = MASTER_HDR1;
    frame[2] = type;
    frame[3] = seq & 0xFF;
    frame[4] = (seq >> 8) & 0xFF;
    for (int i = 0; i < FIELD_COUNT; i++) {
        int16_t v = fields ? fields[i] : 0;
        frame[5 + i * 2] = v & 0xFF;
        frame[5 + i * 2 + 1] = (v >> 8) & 0xFF;
    }
    uint16_t c = crc16_modbus(frame + 2, FRAME_LEN - 4);   // 对 type..payload 计算
    frame[58] = c & 0xFF;
    frame[59] = (c >> 8) & 0xFF;
    HOST_SERIAL.write(frame, FRAME_LEN);
}

// ============ 解析下行主控帧（60 字节），返回是否成功 ============
static bool parse_master_down(const uint8_t *frame, uint16_t *seq, int16_t *fields) {
    if (frame[0] != MASTER_HDR0 || frame[1] != MASTER_HDR1) return false;
    if (frame[2] != MASTER_DOWN) return false;
    uint16_t c = crc16_modbus(frame + 2, FRAME_LEN - 4);
    uint16_t rc = frame[58] | (frame[59] << 8);
    if (c != rc) return false;
    *seq = frame[3] | (frame[4] << 8);
    for (int i = 0; i < FIELD_COUNT; i++) {
        fields[i] = (int16_t)(frame[5 + i * 2] | (frame[5 + i * 2 + 1] << 8));
    }
    return true;
}

// ============ 采集遥测并组上行帧（占位：字段值示意） ============
static void send_telemetry() {
    int16_t f[FIELD_COUNT] = {0};
    f[0] = (int16_t)(g_seq & 0xFFFF);       // seq（由 build 重写，此处示意）
    f[1] = 0x0001;                          // status: bit0=已上电
    f[2] = (int16_t)(12000);                // voltage_mv（示意）
    f[3] = (int16_t)(3500);                 // current_ma
    f[4] = (int16_t)(450);                  // temp 0.1°C
    f[5] = (int16_t)(analogRead(34));       // rpm_l 占位
    f[6] = (int16_t)(analogRead(35));       // rpm_r 占位
    // ... 其余字段接实际传感器
    build_master(MASTER_UP, g_seq++, f);
}

// ============ 执行下行指令（占位） ============
static void apply_command(const int16_t *f) {
    // f[1] = cmd：bit0=开火 bit1=急停 ...（待商讨）
    // f[2] = vx_mmps, f[3] = wz_0_01, f[6] = fire_rpm ...
    // 此处接电机/摩擦轮/云台执行器
}

void setup() {
    HOST_SERIAL.begin(HOST_BAUD);
    LIDAR_SERIAL.begin(230400);   // 雷达固定 230400
}

void loop() {
    // 1) 雷达帧透传：从雷达串口读字节，找 55 AA 头，凑满 60 字节原样转发
    while (LIDAR_SERIAL.available()) {
        uint8_t b = LIDAR_SERIAL.read();
        // 帧同步：等 55 AA 头
        if (lidar_len == 0 && b != 0x55) continue;
        if (lidar_len == 1 && b != 0xAA) {
            lidar_len = (b == 0x55) ? 1 : 0;   // 连续 55 时保留，等下一个 AA
            continue;
        }
        lidar_buf[lidar_len++] = b;
        if (lidar_len >= FRAME_LEN) {
            HOST_SERIAL.write(lidar_buf, FRAME_LEN);   // 原样透传，不解析
            lidar_len = 0;
        }
    }

    // 2) 下行主控帧：从 CH343 读，找 AA 55 头，凑满 60 字节解析执行
    static uint8_t down_buf[FRAME_LEN];
    static int down_len = 0;
    while (HOST_SERIAL.available()) {
        uint8_t b = HOST_SERIAL.read();
        if (down_len == 0 && b != MASTER_HDR0) continue;
        if (down_len == 1 && b != MASTER_HDR1) {
            down_len = (b == MASTER_HDR0) ? 1 : 0;
            continue;
        }
        down_buf[down_len++] = b;
        if (down_len >= FRAME_LEN) {
            uint16_t seq;
            int16_t fields[FIELD_COUNT];
            if (parse_master_down(down_buf, &seq, fields)) {
                apply_command(fields);
            }
            down_len = 0;
        }
    }

    // 3) 周期上行遥测（50ms → 20Hz，比雷达帧频率低，混在不影响雷达透传）
    if (millis() - last_master_ms >= 50) {
        last_master_ms = millis();
        send_telemetry();
    }
}
