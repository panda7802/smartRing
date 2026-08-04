# smartRing 接口调用文档

## 通用约定

- 正式地址：`https://www.panzhenghao.cn/smartRing`
- 请求和响应编码：UTF-8
- 有 JSON 请求体的请求必须带 `Content-Type: application/json`
- 登录成功后会返回访问令牌。需要登录的接口必须带请求头：
  `Authorization: Bearer <token>`
- token 有效期为 30 天；注销后立即失效。
- 错误统一返回：`{"code":"错误码","message":"错误说明"}`。

> 按当前项目要求，服务端会把密码转换为 32 位小写 MD5 后保存。客户端应通过 HTTPS 发送原始密码，不要自行转换。

## 1. 用户注册

`POST /smartRing/regist`

兼容别名：`POST /smartRing/register`

请求：

```json
{
  "name": "用户名",
  "passwd": "密码"
}
```

成功响应（HTTP 201）：

```json
{
  "message": "注册成功"
}
```

用户名已存在时返回 HTTP 409，错误码为 `USER_EXISTS`。

## 2. 用户登录

`POST /smartRing/login`

请求：

```json
{
  "name": "用户名",
  "passwd": "密码"
}
```

成功响应（HTTP 200）：

```json
{
  "token": "登录令牌",
  "tokenType": "Bearer",
  "expiresAt": "2026-08-16T03:22:33Z",
  "userId": 1
}
```

用户名或密码错误时返回 HTTP 401，错误码为 `LOGIN_FAILED`。

## 3. 用户注销

`POST /smartRing/logout`

请求头：`Authorization: Bearer <token>`

请求：

```json
{}
```

成功响应（HTTP 200）：

```json
{
  "message": "注销成功"
}
```

## 4. 设置赞念重置状态

`POST /smartRing/tasbeeh/reset`

请求头：`Authorization: Bearer <token>`

请求：

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

该接口只把当前用户的重置状态设为 `true`，不会删除或清零服务端当天已经保存的赞念数。每个新注册用户的初始重置状态也是 `true`。

## 5. 同步赞念数

`POST /smartRing/tasbeeh/sync`

请求头：`Authorization: Bearer <token>`

请求：

```json
{
  "count": "15"
}
```

`count` 可以是非负整数或只包含 ASCII 数字的字符串，最大值为
`9007199254740991`。

- 当前用户的重置状态为 `true` 时：服务端当天的数量增加 `count`。
- 当前用户的重置状态为 `false` 时：服务端当天的数量直接设为 `count`。
- 同步成功后，当前用户的重置状态总是变为 `false`。
- “当天”按中国标准时间（UTC+8）的自然日计算。
- 不同用户的数据完全独立。

成功响应（HTTP 200）：

```json
{
  "message": "同步成功",
  "date": "20260725",
  "count": "15",
  "reset": false
}
```

响应中的 `count` 是同步后服务端保存的当天赞念数。

## 6. 获取当前用户每日赞念数

`GET /smartRing/tasbeeh/daily`

请求头：`Authorization: Bearer <token>`

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

`all` 只包含当前登录用户的数据，并按日期从新到旧排列。没有记录时返回
`{"all":[]}`。

## 7. 下载 Android 安装包

`GET /smartRing/app/sr.apk`

成功时返回 APK 文件，响应类型为
`application/vnd.android.package-archive`，下载文件名为 `sr.apk`。

## 常见 HTTP 状态码

| 状态码 | 含义 |
| --- | --- |
| 200 | 请求成功 |
| 201 | 创建成功 |
| 400 | 参数或 JSON 格式错误 |
| 401 | 登录失败、token 缺失、无效或过期 |
| 404 | 接口不存在 |
| 409 | 用户名已存在 |
| 413 | 请求体超过 16 KiB |
| 415 | `Content-Type` 不符合接口要求 |
| 500 | 服务器内部错误 |

## NFC 祈福接口

以下接口都需要 `Authorization: Bearer <token>`。

### 获取当前账户

`GET /smartRing/me`

返回当前账户的服务端 ID 和用户名：

```json
{"userId":1,"name":"alice"}
```

### 登记一枚祈福贴纸

`POST /smartRing/blessings/tags`

```json
{
  "nickname": "小安",
  "message": "愿你平安喜乐",
  "packageName": "com.zx.smartring"
}
```

服务端返回标准 UUID 格式的 `blessingId`，同时在云端保存账户、昵称、祝福语、包名和
登记时间。Android 客户端只把 UUID 和 Android Application Record 写入 NDEF，以适配
容量较小的 NFC 贴纸；Application Record 中的包名用于系统把扫描事件路由到本 APP。

### 通过贴纸 UUID 获取祝福详情

`GET /smartRing/blessings/tags/{blessingId}`

该接口无需登录。UUID 相当于贴纸的随机访问标识，返回昵称、祝福语、发送账户 ID、包名和
登记时间。扫描贴纸后，Android APP 先调用本接口取回展示内容，再记录收到祈福的事件。

### 记录一次收到的祈福

`POST /smartRing/blessings/receive`

```json
{
  "blessingId": "服务端返回的祝福 ID",
  "eventId": "本次扫描生成的 UUID"
}
```

每次物理靠近生成新的 `eventId`；同一 `eventId` 重试不会重复计数。响应中的 `isSelf`
表示发送者与接收者是否为同一账户，自祈福仍会正常保存。

### 获取祈福记录

`GET /smartRing/blessings`

返回 `sent` 和 `received` 两个数组，分别表示当前账户发起和收到的祈福，最多各 200 条，
按时间倒序排列。

管理员登录后可在 `/smartRing/admin/blessings` 分页查看所有账户的每次发送和接收记录；
页面展示双方账户、祝福内容、贴纸 UUID、APP 包名、接收时间及自祈福标记，每页 100 条。

## curl 调用示例

```bash
curl -X POST 'https://www.panzhenghao.cn/smartRing/regist' \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo","passwd":"demo123"}'

curl -X POST 'https://www.panzhenghao.cn/smartRing/login' \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo","passwd":"demo123"}'

curl -X POST 'https://www.panzhenghao.cn/smartRing/logout' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <登录返回的 token>' \
  -d '{}'

curl -X POST 'https://www.panzhenghao.cn/smartRing/tasbeeh/reset' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <登录返回的 token>' \
  -d '{}'

curl -X POST 'https://www.panzhenghao.cn/smartRing/tasbeeh/sync' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <登录返回的 token>' \
  -d '{"count":"15"}'

curl 'https://www.panzhenghao.cn/smartRing/tasbeeh/daily' \
  -H 'Authorization: Bearer <登录返回的 token>'
```

## 数据库设计

数据库文件使用 SQLite，正式环境路径为
`/var/lib/smartRing/smartring.db`。数据库保存用户、登录会话、每个用户每天的赞念数、管理员和管理员会话。

### users

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | INTEGER | 自增主键 |
| name | TEXT | 唯一用户名，区分大小写 |
| passwd_md5 | TEXT | 32 位小写 MD5 |
| created_at | INTEGER | 创建时的 Unix 时间戳 |
| tasbeeh_reset_pending | INTEGER | 重置状态；1 为 true，0 为 false，新用户默认为 1 |

### sessions

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | INTEGER | 自增主键 |
| user_id | INTEGER | 关联 users.id |
| token_hash | TEXT | token 的 SHA-256；数据库不保存 token 明文 |
| created_at | INTEGER | 登录时的 Unix 时间戳 |
| expires_at | INTEGER | 过期时的 Unix 时间戳 |
| revoked_at | INTEGER | 注销时间；未注销时为空 |

### tasbeeh_daily_counts

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| user_id | INTEGER | 关联 users.id，与 count_date 组成联合主键 |
| count_date | TEXT | 中国标准时间的日期，格式 `yyyyMMdd` |
| count | INTEGER | 该用户当天的赞念数 |
| updated_at | INTEGER | 最近同步时的 Unix 时间戳 |

### admin_users

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | INTEGER | 自增主键 |
| name | TEXT | 唯一管理员用户名 |
| passwd_md5 | TEXT | 32 位小写 MD5 |
| created_at | INTEGER | 创建时的 Unix 时间戳 |

### admin_sessions

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | INTEGER | 自增主键 |
| admin_user_id | INTEGER | 关联 admin_users.id |
| token_hash | TEXT | 后台会话 token 的 SHA-256 |
| csrf_hash | TEXT | CSRF token 的 SHA-256 |
| created_at | INTEGER | 登录时的 Unix 时间戳 |
| expires_at | INTEGER | 过期时的 Unix 时间戳 |
| revoked_at | INTEGER | 注销时间；未注销时为空 |
