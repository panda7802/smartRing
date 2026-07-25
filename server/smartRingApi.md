# smartRing 接口文档与使用说明

## 1. 基本信息

- 正式服务地址：`https://www.panzhenghao.cn/smartRing`
- 通信协议：HTTPS
- 字符编码：UTF-8
- JSON 请求必须携带请求头：

```http
Content-Type: application/json
```

- 登录成功后返回 Bearer Token。需要登录的接口必须携带：

```http
Authorization: Bearer <token>
```

- Token 有效期为 30 天，用户注销后立即失效。
- 所有用户数据相互独立。
- 服务端按中国标准时间（UTC+8）划分自然日。

## 2. 通用响应规则

成功响应的 HTTP 状态码为 `200` 或 `201`。

失败时统一返回：

```json
{
  "code": "错误码",
  "message": "错误说明"
}
```

常见 HTTP 状态码：

| 状态码 | 说明 |
| --- | --- |
| 200 | 请求成功 |
| 201 | 注册成功 |
| 400 | 参数或 JSON 格式错误 |
| 401 | 登录失败，或者 Token 缺失、无效、过期 |
| 404 | 接口不存在 |
| 409 | 用户名已存在 |
| 413 | 请求体超过 16 KiB |
| 415 | `Content-Type` 不是 `application/json` |
| 500 | 服务器内部错误 |

## 3. 用户注册

### 请求

```http
POST /smartRing/regist
```

兼容地址：

```http
POST /smartRing/register
```

请求参数：

```json
{
  "name": "用户名",
  "passwd": "密码"
}
```

参数说明：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| name | string | 是 | 1～64 个字符，首尾不能有空格，区分大小写 |
| passwd | string | 是 | 1～128 个字符 |

成功响应（HTTP 201）：

```json
{
  "message": "注册成功"
}
```

说明：

- 新用户注册后的赞念重置状态默认为 `true`。
- 服务端按照项目兼容要求，将密码转换为32位小写MD5后保存。
- 客户端应通过HTTPS发送原始密码，不需要自行计算MD5。
- 用户名已存在时返回 HTTP 409，错误码为 `USER_EXISTS`。

## 4. 用户登录

### 请求

```http
POST /smartRing/login
```

请求参数：

```json
{
  "name": "用户名",
  "passwd": "密码"
}
```

成功响应（HTTP 200）：

```json
{
  "token": "访问令牌",
  "tokenType": "Bearer",
  "expiresAt": "2026-08-24T08:00:00Z"
}
```

响应参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| token | string | 后续认证接口使用的访问令牌 |
| tokenType | string | 固定为 `Bearer` |
| expiresAt | string | Token 的 UTC 过期时间 |

用户名或密码错误时返回 HTTP 401，错误码为 `LOGIN_FAILED`。

## 5. 用户注销

### 请求

```http
POST /smartRing/logout
Authorization: Bearer <token>
Content-Type: application/json
```

请求参数：

```json
{}
```

成功响应（HTTP 200）：

```json
{
  "message": "注销成功"
}
```

注销成功后，当前 Token 立即失效。

## 6. 设置赞念重置状态

### 请求

```http
POST /smartRing/tasbeeh/reset
Authorization: Bearer <token>
Content-Type: application/json
```

请求参数：

```json
{}
```

成功响应（HTTP 200）：

```json
{
  "message": "重置状态已设置",
  "reset": true
}
```

说明：

- 该接口将当前用户的重置状态设置为 `true`。
- 该接口不会直接清零或删除服务端已经保存的当天赞念数。
- 下一次调用同步接口时，上传的 `count` 会增加到服务端当天已有数量中。
- 重置状态属于当前登录用户，不会影响其他用户。

## 7. 同步赞念数

### 请求

```http
POST /smartRing/tasbeeh/sync
Authorization: Bearer <token>
Content-Type: application/json
```

请求参数：

```json
{
  "count": "15"
}
```

参数说明：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| count | string 或 integer | 是 | 非负整数，最大值为 `9007199254740991` |

`count` 可以使用以下两种形式：

```json
{
  "count": "15"
}
```

或者：

```json
{
  "count": 15
}
```

成功响应（HTTP 200）：

```json
{
  "message": "同步成功",
  "date": "20260725",
  "count": "15",
  "reset": false
}
```

响应参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| message | string | 同步结果说明 |
| date | string | 服务端统计日期，格式为 `yyyyMMdd` |
| count | string | 同步后服务端保存的当天赞念数 |
| reset | boolean | 同步成功后固定为 `false` |

同步规则：

1. 如果当前用户的重置状态为 `true`，服务端当天数量增加 `count`。
2. 如果当前用户的重置状态为 `false`，服务端当天数量直接设置为 `count`。
3. 同步成功后，当前用户的重置状态变为 `false`。
4. 每个用户、每个日期的数据分别保存。

示例：

| 操作 | 上传 count | 服务端结果 |
| --- | ---: | ---: |
| 新用户第一次同步 | 5 | 5 |
| 未调用重置，再次同步 | 7 | 7 |
| 调用重置接口 | - | 重置状态变为 true |
| 重置后再次同步 | 3 | 10 |

参数错误时返回 HTTP 400，错误码通常为：

- `INVALID_COUNT`：`count` 缺失、不是非负整数或者超过最大值。
- `COUNT_OVERFLOW`：累加后当天总数超过允许的最大值。

## 8. 获取当前用户每日赞念数

### 请求

```http
GET /smartRing/tasbeeh/daily
Authorization: Bearer <token>
```

该接口没有请求体。

成功响应（HTTP 200）：

```json
{
  "all": [
    {
      "date": "20260725",
      "count": "10"
    },
    {
      "date": "20260724",
      "count": "25"
    }
  ]
}
```

响应参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| all | array | 当前登录用户的每日赞念数，按日期从新到旧排列 |
| all[].date | string | 中国标准时间日期，格式为 `yyyyMMdd` |
| all[].count | string | 该用户在对应日期的赞念数 |

当前用户没有赞念记录时返回：

```json
{
  "all": []
}
```

接口只返回当前 Token 对应用户的数据，不会返回其他用户的数据。

## 9. 下载 Android 安装包

### 请求

```http
GET /smartRing/app/sr.apk
```

该接口不需要登录。

成功时返回 APK 文件：

- Content-Type：`application/vnd.android.package-archive`
- 下载文件名：`sr.apk`

完整下载地址：

`https://www.panzhenghao.cn/smartRing/app/sr.apk`

## 10. 推荐调用流程

```text
注册
  ↓
登录并保存 Token
  ↓
携带 Token 调用同步接口
  ↓
设备需要执行重置时调用 reset
  ↓
下一次 sync 自动执行累加
  ↓
不再使用时调用 logout
```

客户端应安全保存 Token。收到 HTTP 401 后，应清除本地 Token 并要求用户重新登录。

## 11. curl 调用示例

注册：

```bash
curl -X POST 'https://www.panzhenghao.cn/smartRing/regist' \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo","passwd":"demo123"}'
```

登录：

```bash
curl -X POST 'https://www.panzhenghao.cn/smartRing/login' \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo","passwd":"demo123"}'
```

同步赞念数：

```bash
curl -X POST 'https://www.panzhenghao.cn/smartRing/tasbeeh/sync' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <登录返回的 token>' \
  -d '{"count":"15"}'
```

设置重置状态：

```bash
curl -X POST 'https://www.panzhenghao.cn/smartRing/tasbeeh/reset' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <登录返回的 token>' \
  -d '{}'
```

获取当前用户每日赞念数：

```bash
curl 'https://www.panzhenghao.cn/smartRing/tasbeeh/daily' \
  -H 'Authorization: Bearer <登录返回的 token>'
```

注销：

```bash
curl -X POST 'https://www.panzhenghao.cn/smartRing/logout' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <登录返回的 token>' \
  -d '{}'
```

## 12. 管理后台说明

管理后台地址：

`https://www.panzhenghao.cn/smartRing/admin/`

管理后台是浏览器页面，不是 JSON 接口。登录后显示注册用户的：

- 用户 ID
- 用户名
- 注册时间
- 查看赞念日历入口

点击“查看赞念日历”后进入：

```text
/smartRing/admin/user?id=<用户ID>
```

也可以指定月份：

```text
/smartRing/admin/user?id=<用户ID>&month=2026-07
```

日历页面按星期排列，显示该用户每天的赞念数、当月总数，并提供上月和下月切换。该页面必须先登录管理后台，不允许普通用户访问。

## 13. 已停用的旧接口

以下旧接口当前不可用，并返回 HTTP 404：

```text
POST /smartRing/tasbeeh
GET  /smartRing/tasbeeh/list
POST /smartRing/tasbeeh/list
GET  /smartRing/health
```

客户端应使用本文件中的 `/tasbeeh/reset`、`/tasbeeh/sync` 和
`/tasbeeh/daily`。
