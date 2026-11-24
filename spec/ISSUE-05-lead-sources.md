# 来源字典模块（后端 3 接口 + 前端 SourceSheet）

**目标**：实现「发现」来源字典的增删改查。

**前置**：`main` 上已有 Spring Boot 骨架 + 鉴权层（PR #9 已合并）。

**输入文档（在仓库里读）**：
- `CLAUDE.md`
- `docs/PRD_一期_v1.3.md` §9.3（来源字典）、§13.4（错误码）
- `spec/05-开发任务清单.md` ← BE-API-050~052、FE-COMP-003
- `shared/db/V1__init.sql` ← `lead_source`、`lead` 表结构

---

## ⚠️ 文件边界（严格遵守，禁止越界）

**可新增**：

```
crm-server/crm-api/src/main/java/com/crm/api/controller/LeadSourceController.java
crm-server/crm-api/src/main/java/com/crm/api/service/LeadSourceService.java
crm-server/crm-api/src/main/java/com/crm/api/dto/LeadSourceDtos.java
crm-server/crm-api/src/test/java/com/crm/api/controller/LeadSourceControllerTest.java
crm-app/src/api/leadSource.ts
crm-app/src/components/SourceSheet.vue
```

**禁止修改**：

```
❌ crm-server/pom.xml
❌ crm-server/**/security/**
❌ crm-server/**/common/**
❌ crm-server/**/controller/TagController.java（另一个并行任务在写）
❌ crm-app/src/utils/request.ts
❌ crm-app/src/api/tag.ts（另一个并行任务在写）
❌ crm-app/src/components/TagSheet.vue（另一个并行任务在写）
❌ shared/db/**、docs/**、spec/**
```

**如果你需要 TagController 的某个工具方法，不要复制也不要改它，自己在本模块内实现。**

---

## 一、后端：3 个接口

### 1. `POST /lead-sources`（BE-API-050）

**权限**：`@RequireRole(ADMIN)`

**入参**：`{ "name": "新来源" }`
- 长度 1–20，空或超长 → `1001`
- 重名 → `3001`「来源名称已存在」

**逻辑**：
- `sort` = 当前最大 + 10
- 写审计：`action=CREATE`、`entity=lead_source`

**出参**：`{ "id": 8, "name": "新来源", "sort": 80 }`

### 2. `PUT /lead-sources/{id}`（BE-API-051）

**权限**：`@RequireRole(ADMIN)`

**入参**：`{ "name": "改名" }`
- 重名（排除自己）→ `3001`
- 不存在 → `1001`

**逻辑**：
- 改名不改 id，历史 `lead` 记录展示自动跟随
- 写审计：`action=UPDATE`，`diff` 含 `{oldName, newName}`

**出参**：`{ "id": 1, "name": "改名" }`

### 3. `DELETE /lead-sources/{id}`（BE-API-052）

**权限**：`@RequireRole(ADMIN)`

**逻辑**：
- 事务内 count 引用：`select count(*) from lead where source_id = ?`
- 引用数 > 0 → `3002`「该来源下还有 N 条发现记录，请先处理」
- = 0 → 硬删 `lead_source` 行
- 写审计：`action=DELETE`

**出参**：`{ "code": 0, "message": "ok", "data": null }`

---

## 二、前端：`SourceSheet.vue`

**UI 基线**：`docs/crm-phase1-prototype.html`，搜 `#sources` 或「来源」弹层。

**要求**：
- 底部弹层，与标签管理同样的交互模式（列表 + 编辑 + 删除 + 新建）
- 列表项：来源名 + 引用数
- 新建输入框：placeholder「来源名称」
- 删除二次确认

**API**：新建 `crm-app/src/api/leadSource.ts`：
```ts
export const createSource = (name: string) => request.post('/lead-sources', { name })
export const updateSource = (id: number, name: string) => request.put(`/lead-sources/${id}`, { name })
export const deleteSource = (id: number) => request.delete(`/lead-sources/${id}`)
```
（读取走 `GET /meta/dicts` 的 `leadSources` 字段，该接口由另一任务实现；本任务**不要**实现 `/meta/dicts`。）

---

## 三、验收（实际执行，粘贴原始输出）

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package && echo "BUILD OK"

java -jar crm-api/target/crm-api-*.jar > /tmp/src-api.log 2>&1 &
sleep 40

BASE=http://localhost:8080/api/v1
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

echo "===== 1. 新建 ====="
curl -s -X POST $BASE/lead-sources -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"测试来源"}'

echo "===== 2. 重名 → 3001 ====="
curl -s -X POST $BASE/lead-sources -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"测试来源"}'

echo "===== 3. 空名 → 1001 ====="
curl -s -X POST $BASE/lead-sources -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":""}'

echo "===== 4. 改名 ====="
SID=$(psql "postgresql://root:123456@192.168.1.53:5432/crm" -tAc \
  "select id from lead_source where name='测试来源'")
curl -s -X PUT $BASE/lead-sources/$SID -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"已改名来源"}'

echo "===== 5. 删除无引用 → 成功 ====="
curl -s -X DELETE $BASE/lead-sources/$SID -H "Authorization: Bearer $TOKEN"

echo "===== 6. 删除有引用 → 3002 ====="
# 建一条 lead 引用种子来源
psql "postgresql://root:123456@192.168.1.53:5432/crm" -c "
insert into lead(org_name, contact_name, phone, source_id, city, status, owner_id)
select '测试发现','张三','13700000000', s.id, '上海','NEW', 1
from lead_source s where s.name='行业展会' limit 1 on conflict do nothing;"
REF_SRC=$(psql "postgresql://root:123456@192.168.1.53:5432/crm" -tAc \
  "select id from lead_source where name='行业展会'")
curl -s -X DELETE $BASE/lead-sources/$REF_SRC -H "Authorization: Bearer $TOKEN"

pkill -f "crm-api.*jar" || true
```

**预期**：

| # | 期望 |
|---|---|
| 1 | code 0 |
| 2 | 3001 |
| 3 | 1001 |
| 4 | code 0 |
| 5 | code 0 |
| 6 | **3002**，message 含 N |

**清理测试数据**（必须）：
```bash
psql "postgresql://root:123456@192.168.1.53:5432/crm" -c "
delete from lead where org_name='测试发现';
delete from lead_source where name in ('已改名来源','测试来源');"
```

---

## 四、完成后

写 `handoff/TASK-SOURCE-REPORT.md`：
1. 新增文件清单
2. 6 条验收的实际输出
3. 是否修改了禁止文件（应为「否」）
4. 测试数据是否已清理
