# SQ666 BLE 接入协议（APP 开发版）

> 文档状态：基于 2026-07-24 的 SQ666 HCI、Android 蓝牙栈日志和原 APP
> 日志逆向整理。本文只把抓包证据充分的功能定义为可用接口。
>
> 本期范围：发现设备、建立连接、订阅通知、读取/监听赞念计数、重置设备当前
> 赞念计数。

## 1. 快速结论

SQ666 使用一个自定义 GATT 服务完成业务通信：

| 用途 | UUID | 抓包中的 Handle |
|---|---|---:|
| Service | `000056ff-0000-1000-8000-00805f9b34fb` | `0x0015` |
| APP 写命令 | `000033f3-0000-1000-8000-00805f9b34fb` | `0x0017` |
| 设备通知 | `000033f4-0000-1000-8000-00805f9b34fb` | `0x0019` |
| 通知 CCCD | `00002902-0000-1000-8000-00805f9b34fb` | `0x001a` |

APP 不应硬编码 Handle。Handle 只用于抓包对照，运行时必须通过 UUID 查找服务、
特征和描述符。

关键业务命令：

| Command | 方向 | 功能 |
|---:|---|---|
| `0x0033` | SQ666 → APP | 当前赞念计数上报 |
| `0x0038` | APP → SQ666 | 激活默认赞念任务，并设置设备当前计数 |
| `0x0038` | SQ666 → APP | 对激活/设置结果的回显确认 |

读取当前计数不是 GATT Characteristic Read。开启 `33F4` 通知后，设备会主动发送
`0x0033`，APP 从通知中取得当前计数。

重置计数不是删除 APP 历史数据。APP 发送 `0x0038`，将其中的设备当前计数设为
`0`；收到设备的 `0x0038` 回显确认后，才把本地设备计数基线改为 `0`。

## 2. 扫描和识别设备

### 2.1 广播特征

抓包中的设备：

- 名称：`SQ666`
- 地址类型：Public
- 示例地址：`41:42:2C:57:1F:13`
- 广播 Service UUID：`FEF5`、`1812`
- `56FF` 不在广播包中，只能连接并发现服务后确认

不要把示例 MAC 地址写死。不同设备具有不同地址。

推荐识别规则：

1. 优先匹配广播 Service UUID `0000fef5-0000-1000-8000-00805f9b34fb`。
2. 同时检查扫描响应中的名称是否为 `SQ666` 或产品确认的名称前缀。
3. 用户选择设备后立即停止扫描，再发起连接。
4. 连接后必须检查 `56FF/33F3/33F4` 是否齐全；这是最终的兼容性判定。

抓包中的名称来自 Scan Response，因此需要主动扫描才能稳定取得名称。

### 2.2 Android 权限

- Android 12 及以上：运行时申请 `BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT`。
- Android 11 及以下：扫描通常还需要定位权限。
- 权限、蓝牙开关或系统定位条件不满足时，不要进入连接状态机。

## 3. 建立连接

推荐顺序：

```text
开始主动扫描
  → 找到 SQ666
  → 停止扫描
  → connectGatt(autoConnect=false, TRANSPORT_LE)
  → 等待 STATE_CONNECTED
  → requestMtu(185)
  → 等待 onMtuChanged
  → discoverServices
  → 查找 56FF / 33F3 / 33F4
  → 开启 33F4 Notification
  → 等待 CCCD 写成功
  → 等待首个 0x0033 当前计数
```

抓包中连接参数：

- 连接方式：Direct Connect，`autoConnect=false`
- PHY：1M
- APP 请求 MTU：185
- 最终有效 MTU：512

重置帧总长为 23 字节。ATT Write Request 还需要协议开销，因此必须先协商到足够
大的 MTU。建议请求 185，并在 `onMtuChanged` 成功后再发业务命令。若最终 MTU
小于 26，不要自行拆分私有协议帧，应当报告连接初始化失败。

## 4. 开启设备通知

连接并发现服务后：

1. 调用 `setCharacteristicNotification(rxCharacteristic, true)`。
2. 查找 `33F4` 下的 CCCD `2902`。
3. 向 CCCD 写入 `01 00`，即 `ENABLE_NOTIFICATION_VALUE`。
4. 等待 `onDescriptorWrite(..., GATT_SUCCESS)`。
5. 只有此时才把业务通道标记为 Ready。

仅调用 `setCharacteristicNotification()` 不够，它只修改手机本地路由；必须同时
写 CCCD。

抓包没有使用 indication，CCCD 不应写成 `02 00`。

## 5. 私有协议帧

所有已确认业务帧都使用以下结构：

```text
Offset  Size  含义
0       2     Magic，固定 FE FC
2       2     Command，UInt16 Little Endian
4       2     固定/版本字段，当前为 01 00
6       2     固定/版本字段，当前为 01 00
8       2     Payload Length，UInt16 Little Endian
10      N     Payload
```

总帧长：

```text
frameLength = 10 + payloadLength
```

例如电量通知：

```text
FE FC 06 00 01 00 01 00 02 00 53 00
```

所有整数按小端序解析。当前没有发现校验和字段。

通知解析器必须：

- 检查 Magic `FE FC`；
- 至少缓存到 10 字节后再读取 Payload Length；
- 等待 `10 + Payload Length` 字节齐全后再分发；
- 能处理一次回调包含多帧或一帧被分段的情况；
- 遇到非法 Magic 时丢弃到下一个 `FE FC`，不能让整个连接永久失步。

## 6. 读取和监听赞念计数

### 6.1 首次读取

`33F4` 通知启用后，SQ666 会主动发送当前状态。抓包中开启通知约 115 ms 后收到：

```text
FE FC 33 00 01 00 01 00 0B 00
B4 CD 63 6A 00 00 02 00 B4 CD 00
```

这是 `Command = 0x0033`，Payload 长度为 11。

已确认的 Payload 字段：

```text
Payload Offset  Size  含义
0               4     设备时间戳，UInt32 Little Endian
4               2     未知/保留
6               2     当前赞念计数，UInt16 Little Endian
8               3     未知；前两字节在样本中与时间戳低位相关
```

上例：

```text
Payload[6..7] = 02 00
currentCount  = 2
```

当前没有抓到一个独立的“查询当前计数”写命令。正确做法是：

- 开启通知；
- 等待首个合法 `0x0033`；
- 把其中的 `currentCount` 作为设备当前基线。

不要为了“查询”而发送计数为 0 的 `0x0038`，那会真的重置设备计数。

### 6.2 持续监听

用户每按一次设备，SQ666 会发送新的 `0x0033`。日志中计数依次出现：

```text
1 → 2 → 3 → ... → 11
```

建议每台设备、每个任务维护：

```text
lastDeviceCount
lastDeviceTimestamp
resetPending
```

基础增量算法：

```text
首次收到 0x0033：
    只建立基线；是否补记离线增量由产品策略决定

currentCount > lastDeviceCount：
    delta = currentCount - lastDeviceCount

currentCount == lastDeviceCount：
    视为重复包，不增加 APP 总数

currentCount < lastDeviceCount：
    视为设备重置、任务切换或计数回绕
    不允许用负数冲减 APP 历史
```

如果 APP 刚完成重置并收到了 `0x0038(count=0)` 确认，则下一条
`0x0033(count=1)` 应记为一次新的赞念。

APP 的累计赞念历史和设备当前计数应分开：

- 设备当前计数：可以被 `0x0038(count=0)` 清零；
- APP 历史总数：默认不应因设备清零而删除。

### 6.3 去重和断线

- 用“设备地址 + 任务 + 时间戳 + 当前计数”做短期去重。
- 收到相同计数的重复通知时，不重复入账。
- 断线时保留最后确认的基线；重连后用首个 `0x0033` 决定是否存在离线增量。
- 如果无法确认设备是否在离线期间被清零，不要自动产生一个很大的回绕增量。

## 7. 重置设备当前赞念计数

### 7.1 命令定义

抓包确认：`0x0038` 用于激活默认 Prayer/Tasbeeh Task，并同步设备内部当前计数。
把其中的计数字段设为 `0`，即可重置当前赞念计数。

重置完整帧：

```text
FE FC 38 00 01 00 01 00 0D 00
01 01 00 64 00 00 00 00 00 21 00 00 00
```

拆解：

```text
FE FC                   Magic
38 00                   Command = 0x0038
01 00 01 00             固定字段
0D 00                   Payload Length = 13
01                      默认任务启用字段，具体名称待确认
01 00                   默认任务标识/类型，具体名称待确认
64 00                   样本固定为 100，推测为任务目标
00 00 00 00             当前计数 UInt32 LE = 0
21 00 00 00             样本固定为 33，推测为每轮计数
```

只有“当前计数字段位于 Payload offset 5、长度 4、小端序”已通过多个日志样本和
APP 的 `anchor` 日志交叉确认。其余任务字段的业务名称属于高可信推测，开发阶段
应先保持抓包值，不要自行修改。

如果需要保留/设置计数 `2`，对应帧为：

```text
FE FC 38 00 01 00 01 00 0D 00
01 01 00 64 00 02 00 00 00 21 00 00 00
```

### 7.2 确认响应

设备通过 `33F4` 通知回显 `Command = 0x0038`。成功重置时，回包为：

```text
FE FC 38 00 01 00 01 00 0D 00
01 01 00 64 00 00 00 00 00 21 00 00 00
```

APP 必须同时满足以下条件才宣布重置成功：

1. Android `onCharacteristicWrite` 返回 `GATT_SUCCESS`；
2. 在超时时间内收到合法的 `0x0038` 通知；
3. 回包 Payload offset `5..8` 解析出的计数为 `0`。

建议业务超时 2～3 秒。超时后允许用户重试，但同一时间只允许一个在途写命令。

### 7.3 状态更新顺序

```text
用户点击“重置”
  → 标记 resetPending
  → 向 33F3 写入 0x0038(count=0)，Write Type Default
  → 等待 onCharacteristicWrite 成功
  → 等待 33F4 的 0x0038(count=0) 回显
  → lastDeviceCount = 0
  → 清除 resetPending
  → UI 显示设备当前计数 0
```

如果写入失败或没有收到回显：

- 不修改本地基线；
- 不显示重置成功；
- 保持监听 `0x0033`，避免后续通知被遗漏。

抓包验证了两次 APP 重置：

- 设备计数 `11` 后发送 `0x0038(count=0)`，下一次按键上报 `1`；
- 设备计数 `16` 后发送 `0x0038(count=0)`，下一次按键上报 `1`。

## 8. Android/Kotlin 参考实现

以下代码只展示协议关键点，实际项目还需要接入权限、生命周期、重连策略和串行
写队列。

### 8.1 常量

```kotlin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private val SERVICE_UUID =
    UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
private val TX_UUID =
    UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb")
private val RX_UUID =
    UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val CMD_COUNT_REPORT = 0x0033
private const val CMD_ACTIVATE_TASK = 0x0038
```

### 8.2 小端序工具和组帧

```kotlin
private fun u16le(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xff) or
        ((data[offset + 1].toInt() and 0xff) shl 8)

private fun u32le(data: ByteArray, offset: Int): Long =
    (data[offset].toLong() and 0xff) or
        ((data[offset + 1].toLong() and 0xff) shl 8) or
        ((data[offset + 2].toLong() and 0xff) shl 16) or
        ((data[offset + 3].toLong() and 0xff) shl 24)

private fun buildFrame(command: Int, payload: ByteArray): ByteArray {
    require(command in 0..0xffff)
    require(payload.size <= 0xffff)

    return ByteBuffer.allocate(10 + payload.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(0xfe.toByte())
        .put(0xfc.toByte())
        .putShort(command.toShort())
        .putShort(1.toShort())
        .putShort(1.toShort())
        .putShort(payload.size.toShort())
        .put(payload)
        .array()
}
```

### 8.3 生成重置帧

```kotlin
private fun buildResetCountFrame(): ByteArray {
    val payload = byteArrayOf(
        0x01,
        0x01, 0x00,
        0x64, 0x00,
        0x00, 0x00, 0x00, 0x00, // currentCount = 0
        0x21, 0x00, 0x00, 0x00
    )
    return buildFrame(CMD_ACTIVATE_TASK, payload)
}
```

### 8.4 解析完整业务帧

```kotlin
data class Sq666Frame(
    val command: Int,
    val payload: ByteArray
)

private fun parseCompleteFrame(value: ByteArray): Sq666Frame? {
    if (value.size < 10) return null
    if (value[0] != 0xfe.toByte() || value[1] != 0xfc.toByte()) return null

    val command = u16le(value, 2)
    val payloadLength = u16le(value, 8)
    if (value.size != 10 + payloadLength) return null

    return Sq666Frame(
        command = command,
        payload = value.copyOfRange(10, value.size)
    )
}
```

生产实现应在此函数外增加流缓存，以兼容分段帧和粘包。

### 8.5 处理计数通知和重置确认

```kotlin
private fun handleFrame(frame: Sq666Frame) {
    when (frame.command) {
        CMD_COUNT_REPORT -> {
            if (frame.payload.size < 8) return
            val deviceTimestamp = u32le(frame.payload, 0)
            val currentCount = u16le(frame.payload, 6)
            onDeviceCount(deviceTimestamp, currentCount)
        }

        CMD_ACTIVATE_TASK -> {
            if (frame.payload.size < 9) return
            val acknowledgedCount = u32le(frame.payload, 5)
            if (resetPending && acknowledgedCount == 0L) {
                lastDeviceCount = 0
                resetPending = false
                onResetSucceeded()
            }
        }
    }
}
```

### 8.6 写入要求

- 写入特征：`33F3`
- Write Type：`WRITE_TYPE_DEFAULT`
- Android 13 及以上优先使用带 `value` 参数的新写 API
- 等待 `onCharacteristicWrite` 后才能发送下一条命令
- 不要并发调用 `writeCharacteristic`

## 9. 推荐连接状态机

```text
IDLE
  → SCANNING
  → CONNECTING
  → MTU_NEGOTIATING
  → DISCOVERING_SERVICES
  → ENABLING_NOTIFICATION
  → WAITING_INITIAL_COUNT
  → READY
  → DISCONNECTED / ERROR
```

只有 `READY` 状态允许执行重置。

进入 `READY` 的最低条件：

- GATT 已连接；
- MTU 足够；
- `56FF/33F3/33F4/2902` 全部存在；
- CCCD 写入成功；
- 已收到首个合法 `0x0033`，或产品明确允许在没有初始计数时操作。

## 10. 错误处理检查表

- 扫描不到设备：检查权限、蓝牙开关、主动扫描以及 Scan Response 名称。
- 连接后找不到 `56FF`：视为不兼容设备，断开连接。
- CCCD 写失败：不能接收 ACK 和计数，禁止进入 Ready。
- MTU 太小：不能可靠发送 23 字节重置帧。
- 收到未知 Command：记录十六进制原始数据，但不要断开连接。
- `0x0033` 长度不足 8：丢弃该帧。
- 计数突然变小：按重置/任务切换处理，不做负增量。
- 重置只有 ATT 写成功、没有 `0x0038` 回显：按业务失败处理。
- GATT 133、超时或系统断链：关闭旧 GATT，再按退避策略重连。

## 11. 当前证据边界

已确认：

- GATT UUID、通知开通过程和写入类型；
- `0x0033` 当前计数位于 Payload offset `6..7`；
- `0x0038` 当前计数位于 Payload offset `5..8`；
- `0x0038(count=0)` 后设备从 `1` 重新开始上报；
- `0x0038` 成功时设备回显相同命令和计数。

尚未完全确认：

- 帧头两个 `01 00` 字段的正式名称；
- `0x0033` Payload 其余字段的正式定义；
- `0x0038` 中 `100` 和 `33` 的正式字段名称；
- 设备计数达到 `65535` 后的行为；
- 不同 SQ666 固件版本是否完全一致。

上线前建议至少用两台设备和两个固件版本验证：

1. 初次连接读取非零计数；
2. 连续按键和重复通知去重；
3. APP 重置后从 1 重新计数；
4. 断线期间按键，重连后处理离线增量；
5. 设备本地重置后 APP 不产生异常大增量。
