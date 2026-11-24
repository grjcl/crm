# TASK-TAG-REPORT · 标签管理模块（后端 4 接口 + 前端 TagSheet）

- 任务：Issue #15（BE-API-019~022、FE-COMP-002）
- 分支：`agent/tag-management-module-81c03c30ab68`
- 基线：`main` @ `25a727f`（鉴权层 PR #9 已合并）
- 环境：Java 21.0.13.1（Temurin）、Maven Wrapper、Node 24.22.3、PostgreSQL 16.16（`192.168.1.53:5432/crm`）

## 1. 新增文件

| 文件 | 说明 |
|---|---|
| `crm-server/crm-api/src/main/java/com/crm/api/controller/TagController.java` | `GET/POST /api/v1/tags`、`PUT/DELETE /api/v1/tags/{id}`；列表仅需登录，写接口 `@RequireRole(Role.ADMIN)` |
| `crm-server/crm-api/src/main/java/com/crm/api/service/TagService.java` | 名称校验、重名 3001、sort=max+10、单查询 orgCount、删除引用 3002、审计 CREATE/UPDATE/DELETE |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/TagDtos.java` | `NameRequest` / `TagItem{id,name,sort,enabled,orgCount}` / `CreatedTag{id,name,sort}` / `RenamedTag{id,name}` |
| `crm-server/crm-api/src/test/java/com/crm/api/controller/TagControllerTest.java` | 17 条测试：MockMvc + 真实 Service + mock Mapper/JdbcTemplate |
| `crm-app/src/api/tag.ts` | `listTags` / `createTag` / `updateTag` / `deleteTag`，统一从 `{code,message,data}` 取 `data` |
| `crm-app/src/components/TagSheet.vue` | 底部弹层：标签列表（名称/引用数/启用状态）、行内改名、删除确认、底部新增 |
| `handoff/evidence/tag-sheet-before.png` | 改造前 `我的` 页（`main` 代码构建，390×844@2x） |
| `handoff/evidence/tag-sheet-after.png` | 改造后同一 `我的` 页 + TagSheet 打开态（临时预览挂载，390×844@2x） |
| `handoff/TASK-TAG-REPORT.md` | 本报告 |

未修改任何现有文件；未修改 `pom.xml`、`security/**`、`common/**`、`request.ts`、`pages.json`、`shared/db/**`、`docs/**`、`spec/**`。

## 2. 实现要点

### 2.1 后端

- 路径：`/api/v1/tags`；响应体沿用 `ApiResponse`。
- 权限：`GET /tags` 不加 `@RequireRole`（登录即可，未登录由现有 `JwtAuthenticationFilter` 返回 1002）；`POST/PUT/DELETE` 均为 `@RequireRole(Role.ADMIN)`，SALES 由 `RoleInterceptor` 返回 HTTP 403 + code 1003。
- 列表：一条 SQL 取回标签与引用数：
  ```sql
  select t.id, t.name, t.sort, t.enabled, count(o.id) as org_count
  from org_tag t
  left join org_tag_rel r on r.tag_id = t.id
  left join org o on o.id = r.org_id and o.deleted_at is null
  group by t.id, t.name, t.sort, t.enabled
  order by t.sort asc, t.id asc
  ```
  `count(o.id)` 只计入未软删合作商，避免 N+1；已实测软删 org 后 orgCount 立即降为 0。
- 名称：`trim` 后 1–10 字（与 `org_tag.name varchar(10)` 一致）；空 → 1001「请输入标签名称」，超长 → 1001「标签名称不能超过 10 字」；重名（改名排除自身）→ 3001「标签名称已存在」。
- sort：`select coalesce(max(sort), 0) + 10`，空表得到 10；种子最大 30 → 新建 40。
- 改名：只更新 `name`，id 不变，`org_tag_rel` 无需更新；审计 diff 为 `{oldName,newName}`。
- 删除：事务内先 `count(*) from org_tag_rel where tag_id=?`（**含软删 org 的关联行**）；>0 → 3002「该标签下还有 N 家合作商，请先移除」；=0 且不是最后一个 → 硬删；只剩一个 → 1001「至少保留一个标签」（PRD §11.4 / AC-TAG-08）。
- 审计：`audit_log` 写 `action=CREATE/UPDATE/DELETE`、`entity=org_tag`、`entity_id`、`diff`（CREATE/DELETE 为 `{name}`，UPDATE 为 `{oldName,newName}`），带 actor/ip/ua。
- 审计写入说明：`audit_log.diff` 是 `jsonb`，MyBatis 的 `setString` 会报 `column "diff" is of type jsonb but expression is of type character varying`；现有 Mapper 不在本任务允许修改范围，故在 `TagService` 内用 `JdbcTemplate` 执行 `... values (?, ?, ?, ?, ?::jsonb, ?, ?)`（已实测可写）。
- 实体时间字段说明：`OrgTag.createdAt/updatedAt` 是 `LocalDateTime`，而 PG 列为 `TIMESTAMPTZ`，全列查询会因类型转换失败；本模块读取只 `select id/name/sort/enabled`，时间字段交给数据库默认值与触发器。

### 2.2 前端

- `tag.ts`：请求层返回整个 `{code,message,data}` 包体，4 个方法统一取 `data` 后返回类型化结果；类型含 `orgCount`/`enabled`。
- `TagSheet.vue`：
  - `v-model` 控制底部弹层显隐；遮罩 `rgba(16,22,34,.42)`、圆角 20、主色 `#1F3864`、标签 chip `#EDF1F9`/`#3A5078`。
  - 列表展示名称、`orgCount`（N 家合作商）、启用/停用状态；每行右侧「编辑」「删除」。
  - 「编辑」进入行内输入，失焦或点保存提交；重名 3001 回滚原值并 toast「标签名重复」；空名/超长本地拦截。
  - 删除：`orgCount > 0` 直接提示「该标签下还有 N 家合作商，请先移除」；`orgCount = 0` 二次确认「确定删除标签「X」？」；只剩一个时提示「至少保留一个标签」；服务端 3002 同样 toast，不做乐观移除。
  - 底部「+ 新建标签」输入框 + 「保存」；成功后追加到本地列表并清空输入。
  - 弹层按钮文案显式设置为中文（`确定`/`取消`/`知道了`），避免 uni-app H5 默认英文 OK/Cancel。

## 3. 构建与测试（实际执行）

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q test          # EXIT=0
cd crm-server && ./mvnw -q -DskipTests package && echo "BUILD OK"
# BUILD OK

cd crm-app && npm run type-check         # EXIT=0
cd crm-app && npm run build              # DONE  Build complete.
```

后端测试统计（surefire）：

```text
TagControllerTest    Tests run: 17, Failures: 0, Errors: 0
PageQueryTest        Tests run: 5,  Failures: 0, Errors: 0
PhoneMaskerTest      Tests run: 2,  Failures: 0, Errors: 0
JwtServiceTest       Tests run: 4,  Failures: 0, Errors: 0
RoleInterceptorTest  Tests run: 4,  Failures: 0, Errors: 0
```

`TagControllerTest` 覆盖：列表 SALES 可用且只发一条查询、SQL 含 `left join`/`deleted_at is null`/`count(o.id)`/`group by`、空列表返回 `[]`、新建 sort=max+10 与空表 10、空名/11 字 1001、重名 3001、改名审计 oldName/newName、改名不存在 404+1001、改名重名 3001、同名不改写不审计、删除被引用 3002（含 N）、删除最后一个 1001、删除成功 + DELETE 审计、删除不存在 404+1001、SALES 三个写接口 403/1003、未登录 1002。

## 4. 8 步验收（最终代码实际执行，原样输出）

> 本机没有 `psql`（`command not found`），用 PostgreSQL JDBC 驱动执行与任务书等价的 SQL（`/tmp/SqlRun.java`，不入库）。`org.created_by` 为 NOT NULL，测试 org 插入时补了 `created_by=1`。

```text
===== 1. 列表（种子有 3 个：分销/供应/其他）=====
{"code":0,"message":"ok","data":[{"id":1,"name":"分销","sort":10,"enabled":true,"orgCount":0},{"id":2,"name":"供应","sort":20,"enabled":true,"orgCount":0},{"id":3,"name":"其他","sort":30,"enabled":true,"orgCount":0}]}
===== 2. 新建 =====
{"code":0,"message":"ok","data":{"id":11,"name":"测试标签","sort":40}}
===== 3. 重名新建 → 3001 =====
{"code":3001,"message":"标签名称已存在","data":null}
===== 4. 改名 =====
TAG_ID=11
{"code":0,"message":"ok","data":{"id":11,"name":"已改名"}}
===== 5. 空名 → 1001 =====
{"code":1001,"message":"请输入标签名称","data":null}
===== 6. 删除无引用的 → 成功 =====
{"code":0,"message":"ok","data":null}
===== 7. 删除有引用的（先绑定）→ 3002 =====
updateCount=1
updateCount=1
SEED_TAG=1
{"code":3002,"message":"该标签下还有 1 家合作商，请先移除","data":null}
===== 8. SALES 访问管理接口 → 403/1003 =====
updateCount=1
SALES_TOKEN_LEN=228
{"code":1003,"message":"没有权限执行该操作","data":null}
HTTP 403
```

补充（同一最终代码）：

```text
===== orgCount 软删前后 & 删除计数 =====
{"code":0,"message":"ok","data":[{"id":1,"name":"分销","sort":10,"enabled":true,"orgCount":1},{"id":2,"name":"供应","sort":20,"enabled":true,"orgCount":0},{"id":3,"name":"其他","sort":30,"enabled":true,"orgCount":0}]}
updateCount=1
{"code":0,"message":"ok","data":[{"id":1,"name":"分销","sort":10,"enabled":true,"orgCount":0},{"id":2,"name":"供应","sort":20,"enabled":true,"orgCount":0},{"id":3,"name":"其他","sort":30,"enabled":true,"orgCount":0}]}
{"code":3002,"message":"该标签下还有 1 家合作商，请先移除","data":null}
```

| # | 请求 | 期望 | 实测 |
|---|---|---|---|
| 1 | GET /tags | code 0，3 条，含 orgCount | ✅ |
| 2 | POST 新建 | code 0，id/name/sort | ✅ `id=11 sort=40` |
| 3 | 重名 | 3001 | ✅ |
| 4 | 改名 | code 0 | ✅ |
| 5 | 空名 | 1001 | ✅ |
| 6 | 删无引用 | code 0 | ✅ |
| 7 | 删有引用 | 3002，message 含「1 家」 | ✅ |
| 8 | SALES 建标签 | HTTP 403 + 1003 | ✅ |

审计（`org_tag`，两轮验收共 6 条；审计表只增不改，保留）：

```text
CREATE | org_tag | 10 | {"name": "测试标签"}
UPDATE | org_tag | 10 | {"newName": "已改名", "oldName": "测试标签"}
DELETE | org_tag | 10 | {"name": "已改名"}
CREATE | org_tag | 11 | {"name": "测试标签"}
UPDATE | org_tag | 11 | {"newName": "已改名", "oldName": "测试标签"}
DELETE | org_tag | 11 | {"name": "已改名"}
```

## 5. 测试数据清理（实际执行）

```sql
delete from org_tag_rel where org_id in (select id from org where name='测试合作商');
delete from org where name='测试合作商';
delete from sys_user where phone='13900000001';
delete from org_tag where name in ('测试标签','已改名');
```

清理后核对：

```text
org_tag: 1 分销 10 / 2 供应 20 / 3 其他 30
org_tag_rel: 0
org where name='测试合作商': 0
sys_user where phone='13900000001': 0
```

`audit_log` 中本次 `org_tag` 审计保留未删：该表只增不改，删除会破坏审计语义。

## 6. 前端与 UI 证据

- `npm run type-check` EXIT=0；`npm run build` → `DONE Build complete.`
- Playwright + Chromium 实测（H5 构建，390×844，route mock `/api/v1/tags`）：
  - 打开弹层显示 3 行：分销（启用/5 家）、供应（启用/2 家）、其他（停用/0 家）；
  - 计算样式：遮罩 `rgba(16, 22, 34, 0.42)`、弹层 `border-radius: 20px 20px 0 0`、白色背景、底部对齐；
  - 行内改名「分销 → 渠道」成功；
  - 新增「同业」成功，列表变 4 行、输入框清空；
  - 「其他」删除二次确认后成功；
  - 改名重名 → toast「标签名重复」且回滚原值；
  - 删除有 5 家引用的标签被拦截，提示「该标签下还有 5 家合作商，请先移除」；
  - 无 console error / pageerror。
- 截图：`handoff/evidence/tag-sheet-before.png`（`main` 的 `我的` 页，无标签弹层）与 `handoff/evidence/tag-sheet-after.png`（同一页面 + TagSheet 打开态）。
- 证据限制（如实说明）：本任务只交付组件，`pages.json` / `mine.vue` 不在允许修改范围，仓库里没有任何页面挂载 `TagSheet`。after 截图是在临时预览副本（未提交）的 `mine.vue` 中挂载该组件后截取的真实 H5 渲染；预览页与 API 不同源且后端未开 CORS，故 `/api/v1/tags` 由 Playwright route mock 提供数据。接入「我的 → 标签管理」或合作伙伴列表的「⚙ 标签」入口需要后续 FE-MINE / FE-PAGE 任务；在那之前无法用真实页面入口做同一场景的前后对比。

## 7. 与任务书 / PRD 的差异与遗留

1. **本机无 `psql`**：验收脚本中的 SQL 改用 JDBC 执行（同一 SQL、同一库），其余 curl 步骤原样执行。
2. **测试 org 补 `created_by`**：`org.created_by` 为 NOT NULL，任务书示例未写，插入时补 `created_by=1`；不影响被测接口。
3. **`GET /tags` 未实现 `enabled` 查询参数**：`spec/02` 写「可选 enabled，默认 true 只返启用」，但 Issue 明确要求列表展示所有标签（含启用状态），本实现按 Issue 返回全部；前端也据此显示停用标签。若后续需要筛选，可再加可选参数。
4. **删除最后一个标签**：Issue 正文的 DELETE 逻辑未提，但 PRD §11.4、`spec/05` BE-API-022 备注与 AC-TAG-08 要求「至少保留 1 个」；本实现返回 1001「至少保留一个标签」，前端同样拦截。
5. **删除不存在的标签**：Issue 只规定 PUT 不存在 → 404 + 1001；DELETE 采用同样语义（404 + 1001），保持一致。
6. **审计 diff 写入方式**：见 §2.1；若后续 infra 提供统一审计组件，可替换。
7. **启用/停用**：本期无启停接口，`TagSheet` 只展示 `enabled` 状态，不做开关（不发明 UI/接口）。
8. **未修改禁止文件**：否。
