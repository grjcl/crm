# 标签管理模块（后端 4 接口 + 前端 TagSheet）

**目标**：实现标签字典的增删改查。这是 S1 第一个可独立交付的模块。

**前置**：`main` 上已有 Spring Boot 骨架 + 鉴权层（PR #9 已合并），能编译能启动。

**输入文档（在仓库里读）**：
- `CLAUDE.md` ← 环境约定（JAVA_HOME / Maven Wrapper / 数据库）
- `docs/PRD_一期_v1.3.md` §6.2（合作标签）、§13.4（错误码）
- `spec/05-开发任务清单.md` ← BE-API-019~022、FE-COMP-002
- `shared/db/V1__init.sql` ← `org_tag`、`org_tag_rel` 表结构

---

## ⚠️ 文件边界（必须严格遵守，避免与其他并行任务冲突）

**你可以修改/新增的文件**：

```
crm-server/crm-api/src/main/java/com/crm/api/controller/TagController.java      （新增）
crm-server/crm-api/src/main/java/com/crm/api/service/TagService.java            （新增）
crm-server/crm-api/src/main/java/com/crm/api/dto/TagDtos.java                   （新增）
crm-server/crm-api/src/test/java/com/crm/api/controller/TagControllerTest.java  （新增）
crm-app/src/api/tag.ts                                                          （新增）
crm-app/src/components/TagSheet.vue                                             （新增）
```

**你可以修改但必须只加不改删的现有文件**（如需改动，只允许追加，并在报告里说明原因）：

```
crm-server/crm-api/src/main/resources/application.yml   （如确实需要，仅追加配置）
```

**禁止修改**：

```
❌ crm-server/pom.xml（任何模块的）
❌ crm-server/crm-api/.../security/**（鉴权层）
❌ crm-server/crm-api/.../common/**（ApiResponse 等）
❌ crm-app/src/utils/request.ts
❌ crm-app/src/pages.json
❌ shared/db/**、docs/**、spec/**
```

需要新依赖时，**不要改 pom.xml**，用已有依赖实现；确实需要的，在报告里写明"需要新增 XXX 依赖"，由人工处理。

---

## 一、后端：4 个接口

统一路径前缀 `/api/v1`，响应体 `{code, message, data}`（用已有的 `ApiResponse`）。

### 1. `GET /tags`（BE-API-019）

**权限**：登录即可（无 `@RequireRole`）

**出参**：
```json
{ "code": 0, "message": "ok", "data": [
  { "id": 1, "name": "分销", "sort": 10, "enabled": true, "orgCount": 5 }
]}
```

**规则**：
- 按 `sort` 升序
- `orgCount` = 该标签下**未删除**的合作商数
  ```sql
  select count(*) from org_tag_rel r
  join org o on o.id = r.org_id
  where r.tag_id = ? and o.deleted_at is null
  ```
- 一次查询要避免 N+1：用 `left join` + `group by` 一次取回

### 2. `POST /tags`（BE-API-020）

**权限**：`@RequireRole(ADMIN)`

**入参**：`{ "name": "新标签" }`
- `name` 长度 1–10，为空或超长 → `1001`
- 重名 → `3001`「标签名称已存在」

**逻辑**：
- `sort` = 当前最大值 + 10（空表则从 10 开始）
- 写审计 `audit_log`：`action=CREATE`，`entity=org_tag`

**出参**：`{ "id": 4, "name": "新标签", "sort": 40 }`

### 3. `PUT /tags/{id}`（BE-API-021）

**权限**：`@RequireRole(ADMIN)`

**入参**：`{ "name": "改名后" }`
- 重名（排除自己）→ `3001`
- `id` 不存在 → `404` + `code: 1001`

**逻辑**：
- **改名不改 id**，所以 `org_tag_rel` 无需更新
- 写审计：`action=UPDATE`，`diff` 里含 `{oldName, newName}`

**出参**：`{ "id": 1, "name": "改名后" }`

### 4. `DELETE /tags/{id}`（BE-API-022）

**权限**：`@RequireRole(ADMIN)`

**逻辑**：
- **事务内**先 `count` 引用数（`org_tag_rel` 里该 tag_id 的行数，含已软删的 org 也算引用）
  - 实际上：只算 `deleted_at is null` 的 org，还是算全部？
  - **口径**：算全部 `org_tag_rel` 行（含软删 org 的关联），因为硬删 tag 会让软删 org 的标签丢失
- 引用数 > 0 → `3002`「该标签下还有 N 家合作商，请先移除」
- 引用数 = 0 → 删除 `org_tag` 行（**硬删**，标签字典没有软删） + 删除 `org_tag_rel` 里的孤儿行
- 写审计：`action=DELETE`

**出参**：`{ "code": 0, "message": "ok", "data": null }`

---

## 二、前端：`TagSheet.vue`

**UI 基线**：`docs/crm-phase1-prototype.html`，搜 `SCREENS` 里的标签管理弹层（关键词：`⚙ 标签`、`newTag`）。

**要求**：
- 底部弹层（BottomSheet 样式，圆角 20，遮罩 `rgba(16,22,34,.42)`）
- 列表展示所有标签：名称 + `orgCount` + 启用状态
- 每行右侧「编辑」「删除」按钮
- 底部「+ 新建标签」输入框 + 保存按钮
- 删除时二次确认：`confirm("该标签下还有 N 家合作商，确定删除？")`（N>0 时直接提示不能删）

**API 调用**：新建 `crm-app/src/api/tag.ts`，封装 4 个方法：
```ts
export const listTags = () => request.get('/tags')
export const createTag = (name: string) => request.post('/tags', { name })
export const updateTag = (id: number, name: string) => request.put(`/tags/${id}`, { name })
export const deleteTag = (id: number) => request.delete(`/tags/${id}`)
```

**样式**：设计 Token 用 PRD §4.1（主色 `#1F3864`、卡片圆角 15、标签 chip `#EDF1F9`/`#3A5078`）。

---

## 三、验收（必须实际执行，粘贴原始输出）

### 3.1 编译

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package && echo "BUILD OK"
```

### 3.2 启动并测试

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server
java -jar crm-api/target/crm-api-*.jar > /tmp/tag-api.log 2>&1 &
sleep 40

BASE=http://localhost:8080/api/v1

# 登录取 ADMIN token
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

echo "===== 1. 列表（种子有 3 个：分销/供应/其他）====="
curl -s $BASE/tags -H "Authorization: Bearer $TOKEN"

echo "===== 2. 新建 ====="
curl -s -X POST $BASE/tags -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"测试标签"}'

echo "===== 3. 重名新建 → 3001 ====="
curl -s -X POST $BASE/tags -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"测试标签"}'

echo "===== 4. 改名 ====="
TAG_ID=$(curl -s $BASE/tags -H "Authorization: Bearer $TOKEN" | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t['name']=='测试标签': print(t['id']); break")
curl -s -X PUT $BASE/tags/$TAG_ID -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"已改名"}'

echo "===== 5. 空名 → 1001 ====="
curl -s -X POST $BASE/tags -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":""}'

echo "===== 6. 删除无引用的 → 成功 ====="
curl -s -X DELETE $BASE/tags/$TAG_ID -H "Authorization: Bearer $TOKEN"

echo "===== 7. 删除有引用的（需先绑定）→ 3002 ====="
# 先建一个 org 并绑标签（如果 org 接口还没做，用 psql 插入测试数据）
psql "postgresql://root:123456@192.168.1.53:5432/crm" -c "
insert into org(name, type, level, status, owner_id)
values('测试合作商','商旅分销','重点','潜在',1) on conflict do nothing;
insert into org_tag_rel(org_id, tag_id)
select o.id, t.id from org o, org_tag t
where o.name='测试合作商' and t.name='分销'
on conflict do nothing;"
SEED_TAG=$(curl -s $BASE/tags -H "Authorization: Bearer $TOKEN" | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t['name']=='分销': print(t['id']); break")
curl -s -X DELETE $BASE/tags/$SEED_TAG -H "Authorization: Bearer $TOKEN"

echo "===== 8. SALES 访问管理接口 → 403/1003 ====="
# 用 psql 临时建一个 SALES 账号（密码同 admin123 的 hash）
psql "postgresql://root:123456@192.168.1.53:5432/crm" -c "
insert into sys_user(name,phone,role,status,password_hash)
select '测试销售','13900000001','SALES','ACTIVE',password_hash
from sys_user where phone='13800000000' on conflict (phone) do nothing;"
SALES_TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13900000001","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
curl -s -X POST $BASE/tags -H "Authorization: Bearer $SALES_TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"销售建的"}'

pkill -f "crm-api.*jar" || true
```

**预期对照表**：

| # | 请求 | 期望 |
|---|---|---|
| 1 | GET /tags | code 0，3 条（分销/供应/其他），orgCount 字段存在 |
| 2 | POST 新建 | code 0，返回 id/name/sort |
| 3 | 重名 | **3001** |
| 4 | 改名 | code 0 |
| 5 | 空名 | **1001** |
| 6 | 删无引用 | code 0 |
| 7 | 删有引用 | **3002**，message 含「1 家」 |
| 8 | SALES 建标签 | **HTTP 403 + code 1003** |

### 3.3 前端构建

```bash
cd crm-app && npm run build && echo "FRONTEND OK"
```

---

## 四、完成后

写 `handoff/TASK-TAG-REPORT.md`：
1. 新增文件清单
2. **3.2 那张表的 8 条实际输出**（原样粘贴）
3. 与 PRD 的偏差
4. 是否修改了禁止文件（应为「否」）

**测试数据请用 psql 清理**（删除 `测试合作商`、`测试销售`、`已改名` 标签），不要留在库里。
