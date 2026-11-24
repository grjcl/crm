# shared/db · 数据层基线

商旅酒店分销 CRM 一期数据库基线，按 `spec/01-数据模型.md` 组装，PostgreSQL 16 / Flyway 10 口径。

## 文件与执行顺序

| 顺序 | 文件 | 作用 |
|---|---|---|
| 1 | `V1__init.sql` | 建库基线：`set_updated_at()` 函数、16 张表、补充外键（`fk_plan_org` / `fk_follow_deal`）、14 个 `updated_at` 触发器、9 条契约索引与辅助索引、`v_partner_list` 视图、`pg_trgm` 扩展与 `org.name` GIN 索引 |
| 2 | `V2__seed.sql` | 基础字典种子：`org_tag` 3 条（分销/供应/其他）、`lead_source` 7 条 |
| 3 | `V3__admin_seed.sql` | 首个管理员：`13800000000` / 明文 `admin123`（BCrypt 哈希入库）/ `ADMIN` / `商旅分销部` |

顺序不可颠倒：V2 依赖 V1 建好的字典表与唯一索引，V3 依赖 V1 的 `sys_user`。
三个文件均带 `BEGIN … COMMIT`，可整体失败回滚；V2/V3 使用 `ON CONFLICT DO NOTHING`，可重复执行。

说明：

- 表数恰好 16；`org_tag_rel` 用联合主键，是全库唯一没有独立 `id` 的表。
- 触发器只有 14 个：`org_tag_rel` 无 `updated_at`，`audit_log` 只增不改（无 `updated_at`），二者不建触发器。
- `follow.deal_id` 的 FK 采用「先建表、后补约束」方案 A，见 V1 第 17 段。
- 软删只落在 `org` / `follow` / `attachment` 三张表。
- 种子文件名与任务清单里的 `V2__seed_dict.sql` 不同，本仓库统一为 `V2__seed.sql` / `V3__admin_seed.sql`。

## 本地 PostgreSQL 16 执行

```bash
DB="postgresql://root:123456@192.168.1.53:5432/crm"

# 0. 确认连通（应输出 crm|root）
psql "$DB" -tAc "select current_database(), current_user"

# 1. 按顺序执行；ON_ERROR_STOP=1 保证任一语句失败立即停止
psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V1__init.sql
psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V2__seed.sql
psql "$DB" -v ON_ERROR_STOP=1 -f shared/db/V3__admin_seed.sql
```

## 重置

只重建 `public` schema，**不要** `DROP DATABASE`（共享库）：

```bash
psql "$DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```

重置前先确认库里没有别人的数据；重置后重新按顺序执行上面三条命令即可。

## 验收

```bash
DB="postgresql://root:123456@192.168.1.53:5432/crm"

psql "$DB" -tAc "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"          # 16
psql "$DB" -tAc "select count(*) from information_schema.views where table_schema='public' and table_name='v_partner_list'"     # 1
psql "$DB" -tAc "select count(*) from pg_indexes where indexname in ('uk_plan_org','uk_pool_fp')"                               # 2
psql "$DB" -tAc "select count(*) from org_tag"                                                                                  # 3
psql "$DB" -tAc "select count(*) from lead_source"                                                                              # 7
psql "$DB" -tAc "select role from sys_user where phone='13800000000'"                                                           # ADMIN
psql "$DB" -tAc "select count(*) from pg_constraint where conname='fk_follow_deal'"                                            # 1
```

静态检查：

```bash
grep -c "^CREATE TABLE" shared/db/V1__init.sql    # 16
grep -q "CREATE VIEW v_partner_list" shared/db/V1__init.sql && echo "视图 OK"
grep -q "CREATE EXTENSION IF NOT EXISTS pg_trgm" shared/db/V1__init.sql && echo "扩展 OK"
```

## 关于管理员初始密码

`V3__admin_seed.sql` 里的哈希是 `admin123` 的 BCrypt（cost 10），明文只写在注释里供本地联调使用。
生产环境请替换初始密码，并在首次登录后修改。
