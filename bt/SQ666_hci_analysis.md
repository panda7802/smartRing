# SQ666 BLE/HCI 分析

分析对象：

- `btsnooz_hci.log`：标准 btsnoop/HCI UART H4，1747 条记录，时间范围
  `2026-07-24 20:39:41.849892` ～ `20:57:33.526225`
- 同一 bugreport 的系统日志和 React Native 日志
- SQ666：`41:42:2C:57:1F:13`（Public Address）

## 结论摘要

本次有两次 SQ666 连接：

1. `20:39:41.337` 建链，HCI Handle `0x0001`；`20:39:52.801`
   由本机主动断开（HCI Reason `0x16`）。
2. `20:40:20.078` 建链，HCI Handle `0x0003`；这是日志中完整保留的主要会话。

App `com.equantu.itasbeeh.salam` 使用：

- Service：`000056FF-0000-1000-8000-00805F9B34FB`
- 写特征：`000033F3-0000-1000-8000-00805F9B34FB`
  - Value Handle `0x0017`
  - 实际使用 ATT Write Request（`0x12`，有 Write Response）
- 通知特征：`000033F4-0000-1000-8000-00805F9B34FB`
  - Value Handle `0x0019`
  - CCCD Handle `0x001A`
  - App 写入 `01 00`，仅开启 Notification

没有发现 App 订阅其他特征，也没有使用 Indication。

## 1. 扫描

`20:40:14.295` 注册扫描器，Scanner ID 为 5；`20:40:14.301`
开始扫描。App 请求的扫描模式为 0，HyperOS 将其升级成 Balanced（模式 1）。

控制器最终配置：

- Extended Scan
- 1M PHY
- Active Scan
- Scan Interval：730 ms
- Scan Window：182.5 ms
- Controller Filter Policy：0（接收所有广播）
- Duplicate Filtering：关闭
- Duration：0（持续扫描，由 App 主动停止）

`20:40:16.027` 收到 SQ666：

- 地址：`41:42:2C:57:1F:13`
- 地址类型：Public
- RSSI：约 -67/-68 dBm
- 广播服务 UUID：`FEF5`、`1812`（HID）
- 广播原始数据：

```text
02 01 02
05 03 F5 FE 12 18
0F FF 4A 59 41 42 2C 57 1F 13 B4 00 3A 00 3F 00
```

- Scan Response：

```text
06 09 53 51 36 36 36
```

即 Complete Local Name 为 `SQ666`。

`20:40:18.137` App 停止扫描，扫描持续约 3.833 秒。对应时间点之前有一次
用户点击事件，说明大概率是用户在设备列表选择了 SQ666。

## 2. 发起连接

时间线：

| 时间 | 动作 |
|---|---|
| 20:40:18.137 | App 停止扫描 |
| 20:40:19.571 | `connectGatt(autoConnect=false, eattSupport=false)` |
| 20:40:19.577 | GATT Direct Connect，Public Address，PHY=1M |
| 20:40:19.608 | 控制器把 SQ666 加入 Filter Accept List |
| 20:40:19.616 | 发出 LE Extended Create Connection |
| 20:40:20.078 | LE Enhanced Connection Complete，成功 |
| 20:40:20.159 | App 收到 GATT connected 回调 |

连接完成时：

- 手机角色：Central
- HCI Handle：`0x0003`
- 初始 Connection Interval：30 ms
- Slave Latency：0
- Supervision Timeout：5000 ms

App 随后调用 `configureMTU(185)`，但 Android/设备在链路上交换的是：

- SQ666 → 手机：MTU Request 512
- 手机 → SQ666：MTU Response 517
- 最终有效 MTU：512

## 3. GATT 数据库和订阅

设备暴露的服务：

- `FEF5`：9 个私有 128-bit Characteristic
- `56FF`：`33F3`、`33F4`
- `FF12`：`FF15`、`FF14`
- `1800`：Generic Access
- `1801`：Generic Attribute
- `180F`：Battery Service
- `180A`：Device Information
- `1812`：Human Interface Device

App 的业务通道只使用 `56FF`：

| 用途 | UUID | Handle | ATT 行为 |
|---|---|---:|---|
| 手机发送命令 | `33F3` | `0x0017` | Write Request `0x12` |
| 设备上报/响应 | `33F4` | `0x0019` | Notification `0x1B` |
| 开启通知 | `2902` | `0x001A` | 写 `01 00` |

订阅时间：

```text
20:40:21.459 setCharacteristicNotification(33F4, true)
20:40:21.464 Write Request, handle=0x001A, value=01 00
20:40:21.509 Write Response
```

## 4. 私有协议格式

观察到的业务帧统一使用 10 字节头：

```text
FE FC | CMD_LE16 | 01 00 | 01 00 | PAYLOAD_LENGTH_LE16 | PAYLOAD
```

示例：

```text
FE FC 06 00 01 00 01 00 02 00 53 00
```

- Magic：`FE FC`
- Command：`0x0006`
- 两个固定/版本字段：`01 00`、`01 00`（精确定义未知）
- Payload Length：`02 00`
- Payload：`53 00`，App 解释为电量 83%、未充电

## 5. 手机发送的命令

除 CCCD 外，主会话共发送 38 次业务 Write Request，全部写入 Handle
`0x0017`。`btsnooz` 隐私模式只保存每个长值的前三个字节，因此下面能从 HCI
层严格确认的是命令号、完整值长度、时间和响应；无法对所有 TX 帧逐字节复原。

| 命令 | 次数 | TX 值长度 | 可确认用途 |
|---:|---:|---:|---|
| `0x01` | 1 | 17 | 同步日期时间 |
| `0x03` | 1 | 12 | 初始化设置，具体字段未知 |
| `0x0D` | 7 | 23 | 参数设置；日志中末值出现 100/60/20，精确字段未知 |
| `0x0E` | 2 | 11 | 布尔开关，精确用途未知 |
| `0x1A` | 1 | 23 | 初始化/认证风格的数据交换，精确含义未知 |
| `0x1E` | 1 | 16 | 初始化读取/设置，精确含义未知 |
| `0x1F` | 1 | 16 | 初始化读取/设置，精确含义未知 |
| `0x20` | 1 | 16 | 初始化读取/设置，精确含义未知 |
| `0x25` | 1 | 10 | 查询设备信息 |
| `0x2F` | 4 | 11 | 逐条读取历史详情；`0x30` 表示结束 |
| `0x31` | 1 | 20 | 设置设备参数（语言、Azan、振动反馈） |
| `0x34` | 1 | 80 | 下发一批礼拜/祈祷闹钟 |
| `0x35` | 1 | 10 | 查询历史计数 |
| `0x37` | 1 | 10 | 确认历史计数读取完成 |
| `0x38` | 7 | 23 | 激活/恢复默认 Prayer Task，同时同步内部计数 |
| `0x3D` | 3 | 11 | 设置计数锁 |
| `0x3F` | 4 | 11 | 设置屏幕翻转 |

初始化发送顺序：

```text
38 → 1A → 01 → 03 → 25 → 35 → 37
   → 1E → 1F → 20 → 0D → 31 → 3D → 3F
   → 34 → 38 → 2F → 2F
```

## 6. SQ666 接收后返回的数据

主会话收到 169 个 Notification，全部来自 Handle `0x0019`。

主要命令分布：

- `0x33`：117 次，实时念珠计数上报
- `0x38`：7 次，任务激活/状态响应
- `0x06`：5 次，电量/充电状态
- `0x0D`：7 次，参数设置响应
- `0x3D`：3 次，计数锁响应
- `0x3F`：4 次，屏幕翻转响应
- `0x3B`：2 次，Prayer Task 自然退出通知
- 其余为初始化、设备信息和历史同步响应

订阅后立即收到：

```text
FE FC 25 00 01 00 01 00 0C 00
B4 00 41 42 2C 57 1F 13 3A 00 3F 00
```

这是设备信息，其中包含设备地址。

```text
FE FC 1D 00 01 00 01 00 0E 00
B4 CD 63 6A 00 00 12 00 00 00 00 00 00 00
```

```text
FE FC 06 00 01 00 01 00 02 00 53 00
```

App 解释为电量 `0x53 = 83%`、充电状态 `false`。

```text
FE FC 33 00 01 00 01 00 0B 00
B4 CD 63 6A 00 00 02 00 B4 CD 00
```

`0x33` 是实时计数上报；这条中的计数为 2。之后日志中计数持续递增，App
以 `applyDeviceReport` 处理增量。

时间同步响应：

```text
FE FC 01 00 01 00 01 00 07 00 EA 07 07 18 14 28 1A
```

解析为 `2026-07-24 20:40:26`。

设备参数响应：

```text
FE FC 31 00 01 00 01 00 0A 00
00 01 01 00 00 00 00 00 00 00
```

App 解释为：

- `alarmLanguage = 0`
- `azanEnabled = true`
- `vibrationFeedback = true`

计数锁：

```text
FE FC 3D 00 01 00 01 00 01 00 01  # locked
FE FC 3D 00 01 00 01 00 01 00 00  # unlocked
```

屏幕翻转：

```text
FE FC 3F 00 01 00 01 00 01 00 01  # enabled
FE FC 3F 00 01 00 01 00 01 00 00  # disabled
```

Prayer Task 自然退出：

```text
FE FC 3B 00 01 00 01 00 10 00
1F CE 63 6A 00 00 08 00 00 00 00 00 00 00 00 00
```

App 解释为 count=8、category=0、`natural-exit`。

## 7. 日志限制

当前文件名为 `btsnooz_hci.log`，属于 Android 始终开启的内存隐私日志。长 ACL
包在 btsnoop 记录中表现为：

- Original Length 大于 Included Length
- ATT 头和前三个业务字节保留
- 后续业务数据被裁剪

完整 RX 是利用同一个 bugreport 中 App 自己打印的 React Native 日志补齐的；
但 App 没有打印完整 TX 缓冲区，因此本报告不能把所有手机发送帧逐字节列出。

若需要完全复原 TX，必须重新抓取开发者选项启用后的完整
`btsnoop_hci.log`（不是 `btsnooz_hci.log`）。在 HyperOS 中若存在“过滤/完整”
选项，应选择完整记录。

