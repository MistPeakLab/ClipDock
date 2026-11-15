# 数据模型（多端共用）

> 状态：v0.6.0 定稿（2026-11-16）。Windows 版（v0.8）和同步协议（v0.9）都以本文为准。
> 安卓实现见 `app/src/main/java/com/mistpeaklab/clipdock/data/Db.kt`；表结构由数据库 `VERSION` 管理。

## 1. 通用规则

- **ID**：UUID v7（时间有序），字符串形式存储。
- **时间戳**：HLC（混合逻辑时钟），64 位整数 = `毫秒时间(48 位) << 16 | 计数器(16 位)`。本机每次写入取 `max(now_ms << 16, last + 1)`；收到外部数据时 `last = max(last, 外部值)`。
- **删除**：软删除，写 `deleted_at`（HLC，0 表示未删除）。
- **设备 ID**：首次启动生成一个 UUID，存在 `meta.device_id`。
- **单位**：标明“HLC”的字段用上面的 64 位格式；标明“毫秒”的字段就是普通的 Unix 毫秒时间。

## 2. 表

### 2.1 `meta`

```sql
CREATE TABLE meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
```

| key | 含义 |
|---|---|
| `schema_version` | 表结构版本 |
| `device_id` | 本机设备 ID |
| `hlc_last` | 本机见过的最大 HLC |
| `seq_last` | 本机变更序号 |
| `migrated_from_json` | 旧版 `history.json` 的迁移标记 |
| `vault_wrapped` | 隐私模式的数据密钥（用系统密钥库包裹后的 Base64） |

### 2.2 `items`

```sql
CREATE TABLE items (
  id            TEXT PRIMARY KEY,          -- UUID v7
  kind          INTEGER NOT NULL,          -- 0 剪贴板记录 1 常用语 2 文库篇章（v0.7）
  text          TEXT NOT NULL DEFAULT '',  -- 明文；隐藏条目为空串
  cipher        BLOB,                      -- 隐藏条目的密文；NULL 表示未隐藏
  cipher_scheme INTEGER NOT NULL DEFAULT 1,
  hash          TEXT NOT NULL,             -- SHA-256(原文 UTF-8)，去重用；隐藏条目同样计算
  search        TEXT NOT NULL DEFAULT '',  -- 搜索串：小写原文 + 全拼 + 首字母；隐藏条目只含备注
  preview       TEXT NOT NULL DEFAULT '',  -- 列表预览（前 300 字，空白折叠）；隐藏条目为空
  chars         INTEGER NOT NULL,          -- 原文字数（UTF-16 单元）
  note          TEXT,                      -- 用户备注，可搜索
  source_pkg    TEXT,                      -- 来源应用包名
  group_id      TEXT,                      -- 所属分组
  pinned        INTEGER NOT NULL DEFAULT 0,
  hidden        INTEGER NOT NULL DEFAULT 0,
  expire_at     INTEGER,                   -- 到期自动清除（毫秒，验证码用）
  sort_key      REAL,                      -- 常用语手动排序
  title         TEXT,                      -- v0.7 起：文库标题
  source_title  TEXT,                      -- v0.7 起：出处标题
  source_author TEXT,                      -- v0.7 起：作者
  source_url    TEXT,                      -- v0.7 起：原文链接
  extra         TEXT,                      -- v0.7 起：预留的 JSON
  created_at    INTEGER NOT NULL,          -- HLC
  updated_at    INTEGER NOT NULL,          -- HLC；任何内容变化都更新
  used_at       INTEGER NOT NULL,          -- 最近一次复制/粘贴（毫秒，本机字段，不同步）
  use_count     INTEGER NOT NULL DEFAULT 0,-- 本机字段，不同步
  evicted_at    INTEGER,                   -- 因超出条数上限被本机淘汰（毫秒，本机字段，不同步）
  deleted_at    INTEGER,                   -- HLC；0/NULL 表示未删除
  wiped         INTEGER NOT NULL DEFAULT 0,-- 正文已清空，只剩墓碑
  purged        INTEGER NOT NULL DEFAULT 0,-- 预留
  device_id     TEXT NOT NULL,             -- 最后修改它的设备
  seq           INTEGER NOT NULL DEFAULT 0 -- 本机变更序号（调试、增量同步用）
);
CREATE INDEX idx_items_hash   ON items(hash);
CREATE INDEX idx_items_expire ON items(expire_at) WHERE expire_at IS NOT NULL;
```

**一行的五种状态，不能混**

| 状态 | 判定 | 含义 | 同步 |
|---|---|---|---|
| 在列表里 | `deleted_at`、`evicted_at` 为空且 `wiped=0` | 正常显示 | 是 |
| 删除 | `deleted_at` 非空 | 用户的决定 | 是（墓碑） |
| 淘汰 | `deleted_at` 为空、`evicted_at` 非空 | 本机放不下，不算删除 | **否** |
| 正文已清空 | `wiped=1` | 回收站到期或被彻底删除 | 是（墓碑） |
| 物理删除 | 行不存在 | 只用于“被淘汰”的行，或墓碑超过 180 天 | — |

- **淘汰不能标成删除**：手机上限 500、电脑上限 9999 时，手机的淘汰会把电脑上的记录也删掉。
- **隐藏条目**：`text`/`preview` 为空，内容在 `cipher`；`hash` 和 `chars` 仍按原文计算。`search` 只含备注（备注不加密）。
- **单条上限**：1,000,000 字符，超出截断。

### 2.3 `groups`

```sql
CREATE TABLE groups (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  color      INTEGER,
  sort_key   REAL NOT NULL,
  scope      INTEGER NOT NULL DEFAULT 0,   -- 0 收藏分组 1 文库文集
  created_at INTEGER NOT NULL,             -- HLC
  updated_at INTEGER NOT NULL,             -- HLC
  deleted_at INTEGER,
  device_id  TEXT NOT NULL,
  seq        INTEGER NOT NULL DEFAULT 0
);
```

删除分组不删条目：组内条目的 `group_id` 置空。

### 2.4 `app_rules`（本机，不同步）

```sql
CREATE TABLE app_rules (
  pkg   TEXT PRIMARY KEY,
  rule  INTEGER NOT NULL   -- 0 不记录 1 自动隐藏
);
```

## 3. 写入规则

- **新增**：先算 `hash`；存在未删除、同 `kind`、同 `hash` 的行 → 只更新 `used_at`，不新建。
- **内容修改**：`updated_at = hlc.next()`、`seq = ++seq`、`device_id = 本机`。
- **只“用了一下”**（`used_at`、`use_count`）、淘汰、清空正文：不算内容变化，不动 `updated_at`。
- 所有写操作在一条数据库线程上串行执行。

## 4. 导出文件 `.clipdock`

zip 包：

```
manifest.json   格式版本、条数、是否加密、加密参数
data.json       未加密时的内容
data.enc        加密时的内容（data.json 经分块 AES-256-GCM）
```

- 条目字段与 `items` 表同名（`id`、`kind`、`text`、`source_pkg`、`group_id`、`pinned`、`hidden`、`note`、`sort_key`、`created_at`、`updated_at`、`used_at`、`deleted_at`、`evicted`、`expire_at`、`device_id`）。
- 加密：PBKDF2-HMAC-SHA256（310,000 次）派生密钥；每 1 MiB 一块 AES-256-GCM，随机数 = 8 字节文件前缀 + 4 字节块序号（大端），附加校验数据 1 字节表示“是否最后一块”。
- 改动只能**加字段**，不能改已有字段的含义；`format_version` 加一。
