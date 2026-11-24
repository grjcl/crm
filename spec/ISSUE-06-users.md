# 用户列表接口 `GET /users`（给协作人/交接/分配选人用）

**目标**：提供全员列表接口，供后续「协作人多选」「合作交接」「线索分配」「商机负责人」使用。

**前置**：`main` 上已有 Spring Boot 骨架 + 鉴权层（PR #9 已合并）。

**输入文档**：
- `CLAUDE.md`
- `docs/PRD_一期_v1.3.md` §6.6（归属与协作）、§14（权限矩阵）
- `spec/08-契约变更清单.md` ← C-09/A1 决策的完整定义
- `spec/05-开发任务清单.md` ← BE-API-055
- `shared/db/V1__init.sql` ← `sys_user` 表

---

## ⚠️ 文件边界（严格遵守）

**可新增**：

```
crm-server/crm-api/src/main/java/com/crm/api/controller/UserController.java
crm-server/crm-api/src/main/java/com/crm/api/service/UserQueryService.java
crm-server/crm-api/src/main/java/com/crm/api/dto/UserBrief.java
crm-server/crm-api/src/test/java/com/crm/api/controller/UserControllerTest.java
crm-app/src/api/user.ts
```

**禁止修改**：

```
❌ crm-server/pom.xml
❌ crm-server/**/security/**（鉴权层已完成）
❌ crm-server/**/common/**
❌ crm-server/**/controller/TagController.java（并行任务）
❌ crm-server/**/controller/LeadSourceController.java（并行任务）
❌ crm-server/**/service/TagService.java、LeadSourceService.java（并行任务）
❌ crm-app/src/utils/request.ts
❌ crm-app/src/api/tag.ts、leadSource.ts（并行任务）
❌ crm-app/src/components/TagSheet.vue、SourceSheet.vue（并行任务）
❌ crm-app/src/pages.json
❌ shared/db/**、docs/**、spec/**
```

**当前任务是纯后端，不要写任何 Vue 页面/组件。**

---

## 一、接口定义（按 `spec/08-契约变更清单.md` 的 A1 决策）

### `GET /api/v1/users`

**权限**：登录即可（SALES 也要能看到同事名字才能选协作人）

**查询参数**：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | boolean | `true` | 只返回 ACTIVE；**SALES 传 false 仍按 true 处理** |
| `q` | string | — | 模糊匹配 `name` / `employee_no`，最长 40 |

**出参**（**不分页**，全员只有 10 人左右）：
```json
{ "code": 0, "message": "ok", "data": [
  { "id": 1, "name": "管理员", "dept": "商旅分销部", "employeeNo": null,
    "role": "ADMIN", "phoneMasked": "138****0000" }
]}
```

---

## 二、规则（逐条实现）

| 规则 | 说明 |
|---|---|
| **默认只返回 ACTIVE** | `status = 'ACTIVE'` |
| **SALES 传 `enabled=false` 仍只返回 ACTIVE** | 从 `AuthContext` 取当前用户角色判断 |
| **ADMIN 传 `enabled=false` 返回全部** | 含 DISABLED |
| **不返回明文手机号** | 只给 `phoneMasked`（`138****0000`） |
| **不返回密码字段** | `password_hash` 绝对不能出现在任何 DTO 里 |
| **只有本人时返回 `[本人]`** | 不能返回空数组 |
| **增删改后 60 秒内缓存失效** | 可选用缓存；不缓存也可以（10 人的量级） |
| **`q` 模糊匹配** | `name like %q% or employee_no like %q%`，大小写不敏感 |
| **排序** | 按 `name` 升序（用 `zh-x-ICU` collation 更好，没有就用默认） |

**手机号脱敏**：复用已有的 `PhoneMasker`（鉴权任务已实现，在 `com.crm.api.common` 或 `security` 包里，自己找）。**如果找不到，就在本模块内实现一个私有方法，不要改鉴权层的文件。**

---

## 三、验收（实际执行）

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package && echo "BUILD OK"

java -jar crm-api/target/crm-api-*.jar > /tmp/user-api.log 2>&1 &
sleep 40

BASE=http://localhost:8080/api/v1
PSQL="psql postgresql://root:123456@192.168.1.53:5432/crm"

ADMIN_TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

echo "===== 1. ADMIN 获取列表（应只有自己 1 条）====="
curl -s $BASE/users -H "Authorization: Bearer $ADMIN_TOKEN"

echo "===== 2. 加一个 SALES，再查（应 2 条）====="
$PSQL -c "
insert into sys_user(name,phone,dept,role,status,password_hash)
select '王销售','13900000002','销售一部','SALES','ACTIVE',password_hash
from sys_user where phone='13800000000' on conflict (phone) do nothing;"
curl -s $BASE/users -H "Authorization: Bearer $ADMIN_TOKEN"

echo "===== 3. 停用该 SALES，默认查询应不含 ====="
$PSQL -c "update sys_user set status='DISABLED' where phone='13900000002';"
curl -s "$BASE/users" -H "Authorization: Bearer $ADMIN_TOKEN"

echo "===== 4. ADMIN enabled=false 应含停用 ====="
curl -s "$BASE/users?enabled=false" -H "Authorization: Bearer $ADMIN_TOKEN"

echo "===== 5. SALES 登录后传 enabled=false 仍只返回 ACTIVE ====="
$PSQL -c "update sys_user set status='ACTIVE' where phone='13900000002';"
SALES_TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13900000002","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
$PSQL -c "
insert into sys_user(name,phone,dept,role,status,password_hash)
select '李停用','13900000003','销售二部','SALES','DISABLED',password_hash
from sys_user where phone='13800000000' on conflict (phone) do nothing;"
curl -s "$BASE/users?enabled=false" -H "Authorization: Bearer $SALES_TOKEN"

echo "===== 6. q 模糊搜索 ====="
curl -s "$BASE/users?q=销售" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s "$BASE/users?q=13900000002" -H "Authorization: Bearer $ADMIN_TOKEN"

echo "===== 7. 响应里不能有密码或明文手机号 ====="
curl -s $BASE/users -H "Authorization: Bearer $ADMIN_TOKEN" | grep -iE "password|13800000000" && echo "FAIL" || echo "PASS: 无泄漏"

echo "===== 8. 未登录 → 1002 ====="
curl -s $BASE/users

pkill -f "crm-api.*jar" || true

# 清理
$PSQL -c "delete from sys_user where phone in ('13900000002','13900000003');"
```

**预期对照**：

| # | 请求 | 期望 |
|---|---|---|
| 1 | 默认查询 | 1 条（只有 admin） |
| 2 | 加 SALES 后 | 2 条 |
| 3 | SALES 停用后 | 1 条（不含停用） |
| 4 | ADMIN `enabled=false` | 2 条（含停用） |
| 5 | SALES `enabled=false` | **只含 ACTIVE，不含「李停用」** |
| 6 | `q=销售` / `q=手机号` | 命中对应记录 |
| 7 | 泄漏检查 | 无 `password`、无明文手机号 |
| 8 | 无 token | 1002 |

---

## 四、完成后

写 `handoff/TASK-USERS-REPORT.md`：
1. 新增文件清单
2. 8 条验收的实际输出
3. **规则 5（SALES 传 enabled=false）的实际返回**要重点体现
4. 是否修改禁止文件（应为「否」）
5. 测试用户是否已清理
