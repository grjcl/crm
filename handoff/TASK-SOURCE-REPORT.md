# TASK-SOURCE-REPORT · 来源字典模块（后端 3 接口 + 前端 SourceSheet）

- 任务：Issue #11（BE-API-050~052、FE-COMP-003）
- 分支：`agent/lead-source-module-e4e1fe787b08`
- 基线：`main` @ `25a727f`（鉴权层 PR #9 已合并）
- 环境：Java 21.0.13.1（Temurin）、Maven Wrapper 3.9.9、Node 24.22.3、PostgreSQL 16.16（`192.168.1.53:5432/crm`）、Redis 7（`192.168.1.53:6379`）

## 1. 新增文件

| 文件 | 说明 |
|---|---|
| `crm-server/crm-api/src/main/java/com/crm/api/controller/LeadSourceController.java` | `POST/PUT/DELETE /api/v1/lead-sources`，3 个方法均 `@RequireRole(Role.ADMIN)` |
| `crm-server/crm-api/src/main/java/com/crm/api/service/LeadSourceService.java` | 名称校验、重名 3001、sort=max+10、删除引用 3002、审计 CREATE/UPDATE/DELETE |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/LeadSourceDtos.java` | `NameRequest` / `CreatedSource{id,name,sort}` / `RenamedSource{id,name}` |
| `crm-server/crm-api/src/test/java/com/crm/api/controller/LeadSourceControllerTest.java` | 14 条测试：MockMvc + 真 Service + mock Mapper，覆盖角色、校验、排序、审计、删除引用/最后一条 |
| `crm-app/src/api/leadSource.ts` | `listSources()`（读 `/meta/dicts` 的 `leadSources`）+ `createSource` / `updateSource` / `deleteSource` |
| `crm-app/src/components/SourceSheet.vue` | 底部弹层：来源列表（名称 + 引用数）、行内改名、删除二次确认、底部新建、完成 |
| `handoff/evidence/source-sheet-before.png` | 改造前 `我的` 页（`main` 构建，390×844@2x） |
| `handoff/evidence/source-sheet-after.png` | 改造后 `我的` 页 + SourceSheet 打开态（临时挂载预览，390×844@2x） |
| `handoff/TASK-SOURCE-REPORT.md` | 本报告 |

未修改任何现有文件；未修改 `pom.xml`、`security/**`、`common/**`、`TagController.java`、`request.ts`、`tag.ts`、`TagSheet.vue`、`shared/db/**`、`docs/**`、`spec/**`。

## 2. 实现要点

### 2.1 后端

- 路径：`/api/v1/lead-sources`（与现有 Controller 一致，自行带 `/api/v1` 前缀）。
- 权限：3 个写接口均 `@RequireRole(Role.ADMIN)`；未登录 1002、SALES 1003/HTTP 403（由现有 `RoleInterceptor` 完成）。
- 名称校验：`trim` 后 1–10 字；空 → 1001「请输入来源名称」；超长 → 1001「来源名称不能超过 10 字」。
- 重名：创建/改名（排除自身）均查重 → 3001「来源名称已存在」；唯一索引并发冲突也映射为 3001。
- sort：`select sort from lead_source order by sort desc limit 1`，`max + 10`，空表从 10 开始（种子 7 条 → 新来源 80）。
- 改名：只更新 `name`（id 不变），历史 `lead` 通过 `source_id` 自动显示新名。
- 删除：事务内 `count(*) from lead where source_id=?`；>0 → 3002「该来源下还有 N 条发现记录，请先处理」；=0 且不是最后一条 → 硬删；只剩 1 条 → 3002「至少保留一个来源」（AC-SRC-04）。
- 审计：`audit_log` 写 `action=CREATE/UPDATE/DELETE`、`entity=lead_source`、`entity_id`、`diff`（CREATE/DELETE 为 `{name}`，UPDATE 为 `{oldName,newName}`），并带 actor/ip/ua。
- 审计写入说明：`audit_log.diff` 是 `jsonb`，MyBatis 的 `setString` 直接写会报 `column "diff" is of type jsonb but expression is of type character varying`；现有 Mapper 不含自定义 SQL 且本任务不能改 infra，故在同一 Service 内用 `JdbcTemplate` 执行 `... values (?,...,?::jsonb,...)`（已实测可写）。
- 实体时间字段说明：`LeadSource.createdAt/updatedAt` 是 `LocalDateTime`，而 PG 列为 `TIMESTAMPTZ`，全列查询会报 `Cannot convert the column of type TIMESTAMPTZ`；因此本模块所有读取只 `select` 需要的 `id/name/sort` 列，不读时间字段，也不修改实体。

### 2.2 前端

- `leadSource.ts`：请求层返回的是 `{code,message,data}` 包体，写接口统一取 `data` 后再返回类型化结果；读取 `GET /meta/dicts` 的 `data.leadSources`（该接口由另一任务实现，本任务未实现）。
- `SourceSheet.vue`：
  - `v-model` 控制底部弹层显隐；打开时加载来源列表；遮罩 `rgba(16,22,34,.42)`、圆角 20、主色 `#1F3864`，与原型 `sourcesSheet` 对齐。
  - 行内改名失焦保存；重名/空名/超长回滚为原值并 toast 服务端消息。
  - 删除：`uni.showModal` 二次确认；只剩 1 条时前端先拦「至少保留一个来源」；`refCount>0` 时直接提示「该来源下还有 N 条发现记录，请先处理」；服务端 3002 同样 toast。
  - 底部新建输入框 placeholder「来源名称」、`maxlength=10`；成功后追加到本地列表并清空输入。
  - 列表项按 `refCount` 显示「N 条发现」；当前 `/meta/dicts` 契约只有 `id/name/sort`，若上游不提供计数则不显示该副标题（见 §7）。

## 3. 构建与测试（实际执行）

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package && echo "BUILD OK"
# BUILD OK

cd crm-server && ./mvnw -q test
# 全部通过，见下

cd crm-app && npm run type-check   # EXIT=0
cd crm-app && npm run build        # DONE Build complete.
```

后端测试统计（surefire）：

```text
LeadSourceControllerTest  Tests run: 14, Failures: 0, Errors: 0
PageQueryTest             Tests run: 5,  Failures: 0, Errors: 0
PhoneMaskerTest           Tests run: 2,  Failures: 0, Errors: 0
JwtServiceTest            Tests run: 4,  Failures: 0, Errors: 0
RoleInterceptorTest       Tests run: 4,  Failures: 0, Errors: 0
```

`LeadSourceControllerTest` 覆盖：创建 sort=80、空表 sort=10、重名 3001、空名/11 字 1001、改名审计 oldName/newName、改名不存在 1001、改名重名 3001、删除被引用 3002（message 含 3）、删最后一条 3002、删除成功 + DELETE 审计、删除不存在 1001、SALES 三个接口均 403/1003、未登录 1002。

## 4. 六步验收（原样输出）

> 本机没有 `psql`（`command not found`），改用 PostgreSQL JDBC 驱动执行同样的 SQL（`/tmp/sqlrun/Sql.java`，不入库）。另外任务书脚本有两处与仓库现状不一致，已做等价替换并在 §7 说明：`lead.status` 用 CHECK 允许的「待跟进」；种子来源名为「展会」而非「行业展会」。

```text
TOKEN_LEN=227
===== 1. 新建 =====
{"code":0,"message":"ok","data":{"id":22,"name":"测试来源","sort":80}}
===== 2. 重名 → 3001 =====
{"code":3001,"message":"来源名称已存在","data":null}
===== 3. 空名 → 1001 =====
{"code":1001,"message":"请输入来源名称","data":null}
===== 4. 改名 =====
SID=22
{"code":0,"message":"ok","data":{"id":22,"name":"已改名来源"}}
===== 5. 删除无引用 → 成功 =====
{"code":0,"message":"ok","data":null}
===== 6. 删除有引用 → 3002 =====
UPDATE 1
REF_SRC=2
引用条数=1
{"code":3002,"message":"该来源下还有 1 条发现记录，请先处理","data":null}
===== 补充：超长名（11 字）→ 1001 =====
{"code":1001,"message":"来源名称不能超过 10 字","data":null}
===== 审计（最近 6 条 lead_source） =====
DELETE|lead_source|22|{"name": "已改名来源"}
UPDATE|lead_source|22|{"newName": "已改名来源", "oldName": "测试来源"}
CREATE|lead_source|22|{"name": "测试来源"}
```

| # | 期望 | 实测 |
|---|---|---|
| 1 | code 0 | ✅ `code 0`，`sort=80`（种子最大值 70 + 10） |
| 2 | 3001 | ✅ |
| 3 | 1001 | ✅ |
| 4 | code 0 | ✅ |
| 5 | code 0 | ✅ |
| 6 | 3002，message 含 N | ✅ `还有 1 条发现记录` |

## 5. 测试数据清理（实际执行）

```sql
delete from lead where org_name='测试发现';          -- UPDATE 1
delete from lead_source where name in ('已改名来源','测试来源'); -- UPDATE 0（第 5 步已删）
```

清理后核对：

```text
select count(*) from lead where org_name='测试发现';  -> 0
select count(*) from lead_source;                     -> 7
select id,name,sort from lead_source order by sort;   -> 1 转介绍 10 / 2 展会 20 / ... / 7 招标 70
```

`audit_log` 中本次测试产生的 3 条 `lead_source` 记录保留未删：该表为只增不改的审计日志，删除会破坏审计语义；任务书清理清单也只包含 `lead` 与 `lead_source`。

## 6. 前端与 UI 证据

- 前端构建：`npm run type-check` EXIT=0；`npm run build` → `DONE Build complete.`
- 组件交互实测（Playwright + Chromium，H5 构建 + 临时 mock `/meta/dicts`）：
  - 打开显示 7 条来源，名称与「N 条发现」正确；
  - 行内改名 → toast「已重命名为「老客户推荐」」且值保留；
  - 新增「新来源」→ 列表变 8 条、输入框清空、toast「已添加来源「新来源」」；
  - 删除 → 二次确认「确定删除来源「新来源」？」→ 确认后回到 7 条、toast「已删除」；
  - 无 console error / pageerror。
- 截图（390×844、2x、浅色主题）：
  - `handoff/evidence/source-sheet-before.png`：`main` 的 `我的` 页，SourceSheet 不存在；
  - `handoff/evidence/source-sheet-after.png`：同一 `我的` 页，SourceSheet 打开态。
- 证据限制（如实说明）：本任务只交付组件，`pages.json` / `mine.vue` 不在允许修改范围，仓库里没有任何页面挂载 `SourceSheet`。上图的 after 是在临时预览副本（未提交）的 `mine.vue` 中挂载该组件、并用 mock `/meta/dicts` 提供数据后截取的真实 H5 渲染；不是已提交的页面集成。接入「我的 → 来源字典」需要后续 FE-MINE 任务；在那之前无法用真实页面入口做同一场景的前后对比。

## 7. 与任务书的差异 / 遗留（需人工知悉）

1. **名称上限 10 字 vs 任务书 1–20 字**：`shared/db/V1__init.sql` 的 `lead_source.name` 是 `varchar(10)`，`spec/04` R-VAL-28 也写「超过 10 字拒绝」；`shared/db` 禁止修改，若按 20 字放行，11–20 字会在入库时报 5000。因此本实现按契约取 10 字；六步验收与 AC 均不受影响。若业务确实要 20 字，需要单独改库（如 `varchar(20)`）后再放宽。
2. **任务书验收脚本与仓库现状不一致**：
   - 本机无 `psql`，用 JDBC 执行相同 SQL；
   - `lead.status` 的 CHECK 只允许「待跟进/已联系/已转合作商/已丢弃」，脚本里的 `'NEW'` 会违反约束，改用「待跟进」；
   - V2 种子来源名是「展会」，没有「行业展会」，第 6 步改用「展会」（id=2）并造 1 条引用。
3. **删除最后一条来源**：任务书接口逻辑未写，但 `AC-SRC-04` 与原型「至少保留一个来源」要求拒绝；本实现返回 3002「至少保留一个来源」。
4. **`/meta/dicts` 未实现**（任务书明确不要实现）：`SourceSheet` 的读取依赖该接口返回 `leadSources`；其缓存失效也由该任务负责。
5. **「引用数」数据来源**：`spec/02` 的 `leadSources` 只有 `id/name/sort`，没有计数；组件在 `refCount` 存在时展示「N 条发现」，否则不展示。要让真实页面显示引用数，需要 `/meta/dicts` 任务补 `refCount`（或后续提供来源计数接口）。
6. **审计 diff 写入方式**：因 `jsonb` + 禁止改 infra/common，使用 Service 内 `JdbcTemplate` + `?::jsonb`；若后续 infra 提供统一的审计写入组件，可替换。
