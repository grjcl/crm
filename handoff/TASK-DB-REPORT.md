# TASK-DB-REPORT · 数据层基线（16 表 + 视图 + 扩展 + 种子）

- 任务：DB-MIG-001 / DB-TBL-01..16 / DB-VIEW-001 / DB-EXT-001 / DB-SEED-001 / DB-SEED-002
- 依据：`spec/01-数据模型.md`（§1 迁移顺序、§2 逐表 DDL、§4 视图、§5 初始化数据）
- 环境：已在运行的 PostgreSQL 16.16（`192.168.1.53:5432/crm`），**未使用 docker**，未新建临时实例
- 执行前提：执行前 `public` schema 表数为 0（确认无他人数据），因此本次是从空 schema 的一次完整执行

## 1. 交付物与规模

| 文件 | 内容 | 语句规模 |
|---|---|---|
| `shared/db/V1__init.sql` | 函数 1 + 表 16 + `ALTER TABLE` 2 + 触发器 14 + 索引 27 + 视图 1 + 扩展 1 | 16 张表、27 条索引（其中 9 条契约索引）、1 视图、1 扩展、14 触发器、2 条补充外键 |
| `shared/db/V2__seed.sql` | `org_tag` 3 行、`lead_source` 7 行 | 2 条 `INSERT` |
| `shared/db/V3__admin_seed.sql` | 管理员 1 行 | 1 条 `INSERT` |
| `shared/db/README.md` | 用途/顺序、本地 PG16 执行、重置与验收 | — |
| `handoff/TASK-DB-REPORT.md` | 本报告 | — |

`V1__init.sql` 的段序与任务单 21 段一致：0 函数 → 1–16 表 → 17 补充外键（`fk_plan_org`、`fk_follow_deal`）→ 18 触发器 → 19 索引 → 20 视图 → 21 扩展与 GIN 索引。

## 2. 实际执行与输出（原样粘贴）

### 2.1 连接确认

```
$ psql "$DB" -tAc "select current_database(), current_user"
crm|root

$ psql "$DB" -tAc "select version()"
PostgreSQL 16.16 (Debian 16.16-1.pgdg13+1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
```

### 2.2 三个迁移（`ON_ERROR_STOP=1`）

```
$ psql "$DB" -tAc "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"
0

$ psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V1__init.sql
...（建表/触发器/索引逐条 CREATE，末尾）
CREATE VIEW
CREATE EXTENSION
CREATE INDEX
COMMIT
exit=0

$ psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V2__seed.sql
BEGIN
INSERT 0 3
INSERT 0 7
COMMIT
exit=0

$ psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V3__admin_seed.sql
BEGIN
INSERT 0 1
COMMIT
exit=0
```

重复执行 V2/V3（幂等性，`ON CONFLICT DO NOTHING`）：

```
$ psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V2__seed.sql
BEGIN
INSERT 0 0
INSERT 0 0
COMMIT
$ psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V3__admin_seed.sql
BEGIN
INSERT 0 0
COMMIT
```

### 2.3 五项验收

```
$ psql "$DB" -tAc "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"
16

$ psql "$DB" -tAc "select count(*) from information_schema.views where table_schema='public' and table_name='v_partner_list'"
1

$ psql "$DB" -tAc "select count(*) from pg_indexes where indexname in ('uk_plan_org','uk_pool_fp')"
2

$ psql "$DB" -tAc "select count(*) from org_tag"
3
$ psql "$DB" -tAc "select count(*) from lead_source"
7

$ psql "$DB" -tAc "select role from sys_user where phone='13800000000'"
ADMIN
```

### 2.4 表名与字段抽查

```
$ psql "$DB" -tAc "select count(*) from information_schema.tables where table_name='follow_plan'"
1
$ psql "$DB" -tAc "select count(*) from information_schema.tables where table_name='plan'"
0
$ psql "$DB" -tAc "select count(*) from information_schema.tables where table_name='sys_user'"
1
$ psql "$DB" -tAc "select count(*) from information_schema.tables where table_name='user'"
0
$ psql "$DB" -tAc "select count(*) from information_schema.columns where table_name='org' and column_name='collaborator_ids'"
1
$ psql "$DB" -tAc "select count(*) from information_schema.columns where table_name='follow_plan' and column_name='version'"
1
$ psql "$DB" -tAc "select count(*) from pg_constraint where conname='fk_follow_deal'"
1
$ psql "$DB" -tAc "select count(*) from pg_constraint where conname='fk_plan_org'"
1
```

### 2.5 补充核验

表清单（恰好 16 张，无 `role` 表、无 flyway 历史表）：

```
attachment
audit_log
channel
contact
deal
follow
follow_plan
lead
lead_pool
lead_source
org
org_invoice
org_supply
org_tag
org_tag_rel
sys_user
```

触发器 14 个（`org_tag_rel` / `audit_log` 按 spec 不建）：

```
$ psql "$DB" -tAc "select count(*) from information_schema.triggers where trigger_schema='public'"
14
```

`updated_at` 触发器确实生效（显式写入过期值后被触发器覆盖，事务回滚）：

```
BEGIN;
INSERT INTO sys_user (name, phone, password_hash) VALUES ('触发器自检','13900000001','x');
UPDATE sys_user SET name='触发器自检2', updated_at='2000-01-01T00:00:00Z' WHERE phone='13900000001';
SELECT updated_at > '2001-01-01' AS trigger_overrode_stale_value FROM sys_user WHERE phone='13900000001';
 t
ROLLBACK;
```

BCrypt 哈希与明文 `admin123` 匹配（临时 `pgcrypto` 在事务内校验后回滚）：

```
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SELECT crypt('admin123',(SELECT password_hash FROM sys_user WHERE phone='13800000000'))
     = (SELECT password_hash FROM sys_user WHERE phone='13800000000');
 t
ROLLBACK;
```

`pg_trgm` 已安装，视图可查询：

```
$ psql "$DB" -tAc "select extname||' '||extversion from pg_extension where extname='pg_trgm'"
pg_trgm 1.6
$ psql "$DB" -tAc "select count(*) from v_partner_list"
0
```

### 2.6 静态检查

```
$ grep -c "^CREATE TABLE" shared/db/V1__init.sql
16
$ grep -c "^CREATE INDEX\|^CREATE UNIQUE INDEX" shared/db/V1__init.sql
27
$ grep -c "^CREATE TRIGGER" shared/db/V1__init.sql
14
视图 OK
扩展 OK
协作人字段 OK
延迟 FK OK
```

`float` / `double precision` / `real` 全文无命中；`deleted_at` 仅出现在 `org` / `follow` / `attachment` 三张表的建表语句中。

## 3. 与 `spec/01-数据模型.md` 的偏差

| # | 偏差 | 原因 |
|---|---|---|
| 1 | `org_tag.sort` 用 10/20/30，任务单正文写 1/2/3 | 任务单同一句说「具体名称见 spec §5」，而 spec §5 明确 `分销(10)/供应(20)/其他(30)`；以 spec 为准。仅影响展示顺序间隔，不影响语义 |
| 2 | `lead_pool.channel_id` 增加 `ON DELETE SET NULL` | spec §2.10 DDL 未写，但同节说明「保留 FK 并置 NULL，需 `ON DELETE SET NULL`」；按该结论落地，未改列定义 |
| 3 | 第 17 段同时补 `fk_plan_org` | spec §2.13 / C-06 要求 `follow_plan.org_id` 建 FK，但 §1 列表只提 `fk_follow_deal`；两条一起放在补充外键段 |
| 4 | GIN 索引命名 `idx_org_name_trgm` | 任务单 DB-EXT-001 只要求「名称 GIN 索引」，spec 未给名字；`ON org USING gin (name gin_trgm_ops)` |
| 5 | 触发器 14 个而非「× 16 表」 | `org_tag_rel` 无 `updated_at`、`audit_log` 只增不改，spec §2.6 / §2.16 明确二者不建触发器 |
| 6 | 索引与触发器集中在第 18/19 段，未跟随各表建表语句 | 按任务单 21 段顺序与 spec §1 的顺序组装；DDL 定义与字段未变 |
| 7 | `follow.deal_id` 建表时不写内联 FK | spec §1 方案 A 的必然结果，FK 在 deal 建好后补 |

以上均为组装口径差异，无字段、类型、约束的删改；16 张表的列定义与 spec §2 逐字一致。

## 4. 未执行 / 限制

- 未使用 docker，也未新建临时 PostgreSQL 实例：任务单要求直接使用已运行的 PG16，本次即在该实例的 `crm` 库上执行。
- 未验证 Java/Spring Security 侧登录（本任务不含后端代码）：BCrypt 哈希用 PostgreSQL `pgcrypto.crypt()` 反向校验明文匹配，cost=10，`$2a$` 前缀可被 Java BCrypt 识别。
- 未执行 reset 后的二次全量重跑：执行前已确认 schema 为空（表数 0），首次执行即为空库验证；reset 命令见 README。
- 中文排序 `COLLATE "zh-x-icu"` 属 BE-SORT-001，不在本任务范围，视图与索引未指定 collation。
