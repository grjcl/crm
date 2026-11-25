# S2 后端：合作商 CRUD + 列表/360 + 查重 + 跟进计划

**目标**：实现合作商模块后端闭环，为后续前端列表/详情/新建页面提供 API。

**前置已完成**：
- 数据层 PR #4 已合并（16 表、视图、索引、种子）
- 工程骨架 PR #7 已合并
- 鉴权登录 PR #9 已合并
- 标签/来源/用户模块 PR #17/#18/#19 已合并

**输入文档**：
- `CLAUDE.md`
- `docs/PRD_一期_v1.3.md` §6（档案）、§7（管理）、§12（数据）、§13.2（接口）
- `spec/01-数据模型.md`
- `spec/02-接口任务.md` 中 `BE-ORG-002`、`BE-DEDUP-001`、`BE-PLAN-001`、`BE-API-005~010`、`BE-API-015~016`
- `spec/04-业务规则与校验.md` 中 `R-DEDUP`、`R-PLAN`、`R-STAT`、`R-PARTNER`

---

## 一、文件边界

### 可以新增或修改

```
crm-server/crm-api/src/main/java/com/crm/api/controller/PartnerController.java
crm-server/crm-api/src/main/java/com/crm/api/controller/PlanController.java
crm-server/crm-api/src/main/java/com/crm/api/service/PartnerService.java
crm-server/crm-api/src/main/java/com/crm/api/service/PartnerQueryService.java
crm-server/crm-api/src/main/java/com/crm/api/service/PartnerDedupService.java
crm-server/crm-api/src/main/java/com/crm/api/service/PlanService.java
crm-server/crm-api/src/main/java/com/crm/api/dto/PartnerDtos.java
crm-server/crm-api/src/main/java/com/crm/api/dto/PlanDtos.java
crm-server/crm-api/src/main/java/com/crm/api/dto/DedupDtos.java
crm-server/crm-api/src/test/java/com/crm/api/controller/PartnerControllerTest.java
crm-server/crm-api/src/test/java/com/crm/api/service/PlanServiceTest.java
crm-server/crm-infra/src/main/java/com/crm/infra/mapper/OrgMapper.java
crm-server/crm-infra/src/main/java/com/crm/infra/mapper/OrgTagRelMapper.java
crm-server/crm-infra/src/main/java/com/crm/infra/mapper/OrgInvoiceMapper.java
crm-server/crm-infra/src/main/java/com/crm/infra/mapper/OrgSupplyMapper.java
crm-server/crm-infra/src/main/java/com/crm/infra/mapper/FollowPlanMapper.java
crm-server/crm-infra/src/main/resources/mapper/**/*.xml
```

如需新增工具类，放在本模块已有的 `common`/`service` 目录并在报告列出。

### 禁止修改

```
❌ crm-server/pom.xml
❌ security/**、common/ApiResponse.java、GlobalExceptionHandler.java
❌ TagController/TagService、LeadSourceController/LeadSourceService、UserController/UserQueryService
❌ crm-app/**（本 Issue 只做后端）
❌ shared/db/**、docs/**、spec/**
```

---

## 二、API 实现

### 1. `GET /api/v1/partners`（BE-API-005）

查询参数：`tag` / `city` / `level` / `status` / `type` / `q` / `owner` / `page` / `size`。

出参分页：
```json
{"code":0,"message":"ok","data":{"list":[{
  "id":1,"name":"测试合作商","shortName":"测试","level":"重点","status":"合作中",
  "type":"商旅分销","city":"上海","owner":{"id":1,"name":"管理员"},
  "tags":[{"id":1,"name":"分销"}],
  "plan":{"date":"2026-12-01","note":"回访","daysFromToday":7,"overdueDays":0}
}],"total":1,"page":1,"size":20}}
```

规则：
- 排序严格为 `(plan_date IS NULL), plan_date, name`
- `q` 空白等同无筛选，匹配 `name`/`short_name`
- 标签筛选、关键词筛选、分页正常叠加
- 分页后批量查询 tags（不能 N+1）
- plan 无记录时 `plan: null`；逾期时 `overdueDays > 0`
- 只返回 `org.deleted_at IS NULL`

### 2. `POST /api/v1/partners`（BE-API-006）

输入包括 `org` 主档 22 字段、`tags[]`、`invoice{}`、`supply{}`。字段定义严格引用 `docs/PRD_一期_v1.3.md` §6.1 和 `spec/01-数据模型.md`。

规则：
- 企业名称必填 2–100；统一社会信用代码按 PRD 正则校验
- `shortName` 为空时取企业名称前 4 个字符
- 合作标签至少 1 个；合作类型/分级/状态必填，有默认值时按 PRD 默认
- `credit_code` 或同名归一化命中 → `3001`
- 只有包含「供应」标签时才校验 `supply` 的条件必填字段
- SALES 传入其他 `ownerId` 时**静默覆盖为当前登录人**；ADMIN 可指定
- 事务内落 `org` + `org_invoice` + `org_supply` + `org_tag_rel`
- 写审计 CREATE

### 3. `GET /api/v1/partners/{id}`（BE-API-007）

返回完整 360：

```
主档 + owner + collaborators + tags + invoice + supply(仅供应标签时)
+ contacts(只读) + attachments + recentFollows(最多2条)
+ plan + metrics
```

metrics 必须实现 `spec/04-业务规则与校验.md` R-STAT-01/R-STAT-03：
- 进行中商机：该 org 下 `stage < 4`
- 本月跟进：当月未删除 follow 数
- 联系人：contact 数
- 下次跟进：plan 日期或「待补」

已软删 org → `1001`；不存在 → `1001`。

### 4. `PUT /api/v1/partners/{id}`（BE-API-008）

规则：
- 局部更新；tags 是整体替换
- SALES 修改 owner → `403 + 1003`
- status **真正发生变化**时写一条系统 follow，不能每次编辑都写
- 写审计 UPDATE，`diff` 包含变更前后字段
- 标签去掉「供应」时**不删除** `org_supply` 行
- 处理 `collaborator_ids`（`bigint[]`）

### 5. `DELETE /api/v1/partners/{id}`（BE-API-009）

- ADMIN only
- 软删：只写 `org.deleted_at`，关联 invoice/supply/follow/deal/attachment 保留
- 已删再次删除 → `1001`
- 写审计 DELETE

### 6. `POST /api/v1/partners/check-dup`（BE-API-010）

入参：`{name, creditCode}`；出参：
```json
{"hit":true,"type":"creditCode|name","matches":[{"id":1,"name":"...","creditCode":"..."}]}
```

规则：
- 统一归一化：去括号内容、去空白、全半角统一、英文小写
- `creditCode` 优先于名称
- 名称 similarity > 0.9 视为同名，最多返回 5 条
- 空名称且无 creditCode → `1001`

### 7. `GET /api/v1/partners/{id}/plan`（BE-API-015）

返回 `date/note/owner/version/updatedAt`，无计划时 `data: null`，已删 org → `1001`。

### 8. `PUT /api/v1/partners/{id}/plan`（BE-API-016）

入参：`{date,note,version}`；出参：`{orgId,date,note,version}`。

规则：
- 新建时 version=0
- 更新时使用 `UPDATE ... WHERE org_id=? AND version=?`，影响 0 行 → `4001`
- 成功 version + 1
- 允许过去日期
- 写审计 UPDATE

---

## 三、事务、查询与安全

- 新建/编辑合作商必须是事务；子表写失败不得留下半条主档。
- 列表查询不能对每个合作商逐条查询 tags/plan（禁止 N+1）。
- 所有 controller 通过已有 JWT/AuthContext 获取用户，不从请求体信任 `createdBy`。
- 不记录密码/token 到日志。
- 错误码使用已有枚举，不新增自定义编号：`1001/1002/1003/3001/4001/5000`。

---

## 四、验收

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package && echo "BUILD OK"
java -jar crm-api/target/crm-api-*.jar > /tmp/partner-api.log 2>&1 &
sleep 40
BASE=http://localhost:8080/api/v1
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d '{"phone":"13800000000","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# 1 列表：空库也必须返回 list=[] 而非 null
curl -s "$BASE/partners?page=1&size=20" -H "Authorization: Bearer $TOKEN"

# 2 查重：空名称 → 1001
curl -s -X POST $BASE/partners/check-dup -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":""}'

# 3 新建普通合作商（含标签）
curl -s -X POST $BASE/partners -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"测试 CRM 合作商","shortName":"测试CRM","city":"上海","type":"商旅分销","level":"重点","status":"潜在","tags":[1],"brief":"测试数据"}'

# 4 详情：确认 tags/invoice/supply/contacts/attachments/recentFollows/plan/metrics key 都存在
# 取返回 id 后调用 GET /partners/{id}

# 5 查重：同名应 hit=true/type=name
curl -s -X POST $BASE/partners/check-dup -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"测试 CRM 合作商"}'

# 6 计划新建 → version=1，再用错误 version → 4001
# 7 列表应按计划日期排序，plan.daysFromToday/overdueDays 正确
# 8 编辑 status → 产生一条系统 follow；重复编辑同 status 不应再产生
# 9 软删后详情 1001，列表不显示

# 10 运行项目测试
./mvnw -q test
pkill -f "crm-api.*jar" || true
```

必须新增自动化测试至少覆盖：
- 查重归一化 / creditCode 优先
- plan version 冲突 4001
- SALES 不能改 owner
- status 变化只写一次系统 follow
- 新建事务失败回滚

完成后粘贴真实输出到 `handoff/TASK-PARTNER-REPORT.md`。
