# Issue 正文：数据层基线（16 表 + 视图 + 扩展 + 种子）

> 复制本文件「以下正文」部分到 GitHub Issue 即可。

---

## 以下正文

**目标**：产出 `shared/db/V1__init.sql`，包含 16 张表、9 个契约索引、`v_partner_list` 视图、`pg_trgm` 扩展与基础种子数据。

**输入文档（在仓库里读，不要凭记忆）**：
- `spec/01-数据模型.md` ← **完整 DDL 已现成，直接组装，不要自己设计**
- `spec/05-开发任务清单.csv` ← 任务列表与依赖
- `CLAUDE.md` ← 工程约定

**⚠️ 最重要的一条**：`spec/01-数据模型.md` 里已经有了全部 16 张表的完整 `CREATE TABLE` 语句、索引、触发器和视图定义。**你的工作是按迁移顺序把它们组装成一个文件，不是重新设计表结构。**

---

## 交付物

### 1. `shared/db/V1__init.sql`

严格按 `spec/01-数据模型.md` §1 的迁移顺序组装，共 21 个语句块：

```
0.  函数 set_updated_at()
1.  sys_user
2.  org_tag
3.  lead_source
4.  channel
5.  org                  ← 含 collaborator_ids bigint[]
6.  org_invoice
7.  org_supply
8.  org_tag_rel
9.  contact
10. attachment
11. follow_plan          ← unique(org_id) + version int
12. follow
13. lead
14. lead_pool
15. deal
16. audit_log
17. ALTER TABLE follow ADD CONSTRAINT fk_follow_deal  ← 顺序陷阱，见下
18. 触发器 × 16 表
19. 索引（9 条契约索引 + 辅助索引）
20. 视图 v_partner_list
21. CREATE EXTENSION IF NOT EXISTS pg_trgm + 名称 GIN 索引
```

**顺序陷阱（必须按方案 A）**：
`follow.deal_id` → `deal`，但 `deal` 在第 15 步才建。所以 `follow` 建表时**不写** deal_id 的 FK，等 `deal` 建完后补：
```sql
ALTER TABLE follow ADD CONSTRAINT fk_follow_deal FOREIGN KEY (deal_id) REFERENCES deal(id);
```

### 2. `shared/db/V2__seed.sql`

基础种子数据（与表结构分离，便于测试环境重置）：

- `org_tag`：`分销`、`供应`、`其他`（sort 1/2/3）
- `lead_source`：7 条来源（具体名称见 `spec/01-数据模型.md` §5）

### 3. `shared/db/V3__admin_seed.sql`

首个管理员账号初始化：
- 手机号 `13800000000`
- 密码：BCrypt hash of `admin123`（**注释里写明明文，方便本地测试**）
- role = `ADMIN`
- dept = `商旅分销部`

### 4. `shared/db/README.md`

说明：
- 三个文件的用途与执行顺序
- 如何在本地 PG16 上执行
- 如何重置（`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`）

---

## 硬性要求

| 要求 | 说明 |
|---|---|
| 表名 | `follow_plan`（不是 plan）、`sys_user`（不是 user） |
| 不建 role 表 | 角色是 `sys_user.role` 字段 |
| 表数 | **必须恰好 16 张** |
| 金额字段 | 用 `numeric`，**不用 float** |
| 时间字段 | 一律 `timestamptz` |
| 软删除 | 只有 `org` / `follow` / `attachment` 有 `deleted_at` |
| 二期预留列 | `follow` 表保留 `voice_url`/`voice_sec`/`photos`/`lat`/`lng`/`addr`，加注释 `-- 二期预留，一期不入契约` |
| 不修改 | `docs/`、`spec/` 下的任何文件 |

---
## 验收（必须实际执行，不能只看代码）

**用已经跑着的 PostgreSQL 16 实例**（不要自己起临时库）：

```
host: 192.168.1.53  port: 5432  user: root  password: 123456  database: crm
```

### 第一步：执行三个迁移

```bash
DB="postgresql://root:123456@192.168.1.53:5432/crm"

# 先确认连通
psql "$DB" -tAc "select current_database(), current_user"

# 执行迁移（ON_ERROR_STOP=1 —— 任一语句失败立即停止）
psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V1__init.sql
psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V2__seed.sql
psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V3__admin_seed.sql
```

**共享库注意**：不要 `DROP DATABASE`。如果表已存在要重来，先确认没别人的数据，再：

```bash
psql "$DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```

### 第二步：五项验收（把实际输出原样粘进报告）

```bash
DB="postgresql://root:123456@192.168.1.53:5432/crm"

# 1. 表数必须是 16
psql "$DB" -tAc "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"

# 2. 视图存在 → 期望 1
psql "$DB" -tAc "select count(*) from information_schema.views where table_schema='public' and table_name='v_partner_list'"

# 3. 关键唯一索引 → 期望 2
psql "$DB" -tAc "select count(*) from pg_indexes where indexname in ('uk_plan_org','uk_pool_fp')"

# 4. 种子数据 → 期望 3 和 7
psql "$DB" -tAc "select count(*) from org_tag"
psql "$DB" -tAc "select count(*) from lead_source"

# 5. 管理员账号 → 期望 ADMIN
psql "$DB" -tAc "select role from sys_user where phone='13800000000'"
```

### 第三步：表名与字段抽查

```bash
DB="postgresql://root:123456@192.168.1.53:5432/crm"

# follow_plan 必须存在，plan 必须不存在
psql "$DB" -tAc "select count(*) from information_schema.tables where table_name='follow_plan'"   # 期望 1
psql "$DB" -tAc "select count(*) from information_schema.tables where table_name='plan'"          # 期望 0

# sys_user 存在，user 表不存在
psql "$DB" -tAc "select count(*) from information_schema.tables where table_name='sys_user'"      # 期望 1
psql "$DB" -tAc "select count(*) from information_schema.tables where table_name='user'"          # 期望 0

# 协作人字段
psql "$DB" -tAc "select count(*) from information_schema.columns where table_name='org' and column_name='collaborator_ids'"  # 期望 1

# 乐观锁字段
psql "$DB" -tAc "select count(*) from information_schema.columns where table_name='follow_plan' and column_name='version'"   # 期望 1

# 延迟外键
psql "$DB" -tAc "select count(*) from pg_constraint where conname='fk_follow_deal'"  # 期望 1
```

### 第四步：静态检查（补充，不能代替上面三步）

```bash
grep -c "^CREATE TABLE" shared/db/V1__init.sql                        # 期望 16
grep -q "CREATE VIEW v_partner_list" shared/db/V1__init.sql && echo "视图 OK"
grep -q "CREATE EXTENSION IF NOT EXISTS pg_trgm" shared/db/V1__init.sql && echo "扩展 OK"
```

**如果连不上数据库**，明确报告连接错误，不要用静态检查冒充验收通过。

```bash
grep -c "^CREATE TABLE" shared/db/V1__init.sql        # 期望 16
grep -c "^CREATE INDEX\|^CREATE UNIQUE INDEX" shared/db/V1__init.sql  # 至少 9
grep -q "CREATE VIEW v_partner_list" shared/db/V1__init.sql && echo "视图 OK"
grep -q "CREATE EXTENSION IF NOT EXISTS pg_trgm" shared/db/V1__init.sql && echo "扩展 OK"
grep -q "collaborator_ids" shared/db/V1__init.sql && echo "协作人字段 OK"
grep -q "fk_follow_deal" shared/db/V1__init.sql && echo "延迟 FK OK"
```

---

## 完成后

在仓库根目录写 `handoff/TASK-DB-REPORT.md`：

1. 三个 SQL 文件的语句数、表数、索引数
2. 验收命令的**实际输出**（原样粘贴，不要只说"通过"）
3. 与 `spec/01-数据模型.md` 的偏差（如果有，说明原因）
4. 如果某项验收没做（比如没有 docker），明确写"未执行"而不是跳过

**注意**：如果容器里没有 docker，就**明确报告这一限制**，不要假装验证过。用静态检查的结果也可以，但要如实说明是哪一种。
