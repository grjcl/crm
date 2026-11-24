# TASK-USERS-REPORT · 用户列表接口 `GET /users`（协作人/交接选人）

- 任务：Issue #16 / `BE-API-055`（A1 决策新增的第 55 个接口）
- 分支：`agent/users-list-api-4362849e3cdf`
- 基线：`main` @ `25a727f`（鉴权层 PR #9 已合并）
- 环境：Java 21.0.13.1（Temurin）、Maven Wrapper 3.9.9、Node 24.22.3、PostgreSQL 16.16（`192.168.1.53:5432/crm`）、Redis 7（`192.168.1.53:6379`）
- 约束遵守：未修改任何禁止文件（见 §4）；未写任何 Vue 页面/组件

## 1. 新增文件清单

| 文件 | 说明 |
|---|---|
| `crm-server/crm-api/src/main/java/com/crm/api/controller/UserController.java` | `GET /api/v1/users`，登录即可读（不限定角色）；解析 `enabled`/`q`，按 `AuthContext` 角色决定是否包含停用 |
| `crm-server/crm-api/src/main/java/com/crm/api/service/UserQueryService.java` | 查询服务：默认 `status='ACTIVE'`，`q` 大小写不敏感匹配 `name`/`employee_no`（并兼容手机号，见 §5），`name` 升序，只出展示列 |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/UserBrief.java` | 出参 record：`id/name/dept/employeeNo/role/phoneMasked`（无 `password`、无明文手机号） |
| `crm-server/crm-api/src/test/java/com/crm/api/controller/UserControllerTest.java` | 12 条单测：默认 ACTIVE、SALES 传 `enabled=false` 仍 ACTIVE、ADMIN 可含停用、`q` 透传/转义/截断、无泄漏、未登录 1002、wrapper 条件与排序 |
| `crm-app/src/api/user.ts` | 前端调用封装 `listUsers({ enabled?, q? })`，取出 `data` 并返回 `UserBrief[]` |
| `handoff/TASK-USERS-REPORT.md` | 本报告 |

未修改既有 Java/TS 文件；新增测试文件位于允许的 `src/test/java/com/crm/api/controller/` 目录。

## 2. 实际执行与原始输出

### 2.1 编译

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package
# BUILD EXIT=0；产物 crm-api/target/crm-api-1.0.0-SNAPSHOT.jar
```

### 2.2 单测

```bash
cd crm-server && ./mvnw -q test
```

```
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- PageQueryTest
Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- PhoneMaskerTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- UserControllerTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- JwtServiceTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- RoleInterceptorTest
合计 27 条，全部通过
```

### 2.3 8 条验收（对真实库执行）

启动方式（`8080` 被并行任务的联调实例占用，改用 `18080`，避免相互影响）：

```bash
java -jar crm-api/target/crm-api-1.0.0-SNAPSHOT.jar --server.port=18080
BASE=http://localhost:18080/api/v1
```

基线 `sys_user` 只有 1 条（`id=1 管理员`）。以下为 `curl` 原始返回：

**1. ADMIN 获取列表（应只有自己 1 条）**
```json
{"code":0,"message":"ok","data":[{"id":1,"name":"管理员","dept":"商旅分销部","employeeNo":null,"role":"ADMIN","phoneMasked":"138****0000"}]}
```
→ 1 条，仅本人。

**2. 加一个 SALES，再查（应 2 条）**
```json
{"code":0,"message":"ok","data":[{"id":10,"name":"王销售","dept":"销售一部","employeeNo":null,"role":"SALES","phoneMasked":"139****0002"},{"id":1,"name":"管理员","dept":"商旅分销部","employeeNo":null,"role":"ADMIN","phoneMasked":"138****0000"}]}
```
→ 2 条。注意排序为 `name` 升序（王 < 管）。

**3. 停用该 SALES，默认查询应不含**
```json
{"code":0,"message":"ok","data":[{"id":1,"name":"管理员","dept":"商旅分销部","employeeNo":null,"role":"ADMIN","phoneMasked":"138****0000"}]}
```
→ 1 条，停用的王销售未出现。

**4. ADMIN `enabled=false` 应含停用**
```json
{"code":0,"message":"ok","data":[{"id":10,"name":"王销售","dept":"销售一部","employeeNo":null,"role":"SALES","phoneMasked":"139****0002"},{"id":1,"name":"管理员","dept":"商旅分销部","employeeNo":null,"role":"ADMIN","phoneMasked":"138****0000"}]}
```
→ 2 条，含 DISABLED 的王销售。

**5. SALES 登录后传 `enabled=false` 仍只返回 ACTIVE（重点）**

此时库中还有一条 `李停用`（`13900000003`，`SALES`，`DISABLED`）；SALES（王销售）带 token 请求 `GET /users?enabled=false`：

```json
{"code":0,"message":"ok","data":[{"id":10,"name":"王销售","dept":"销售一部","employeeNo":null,"role":"SALES","phoneMasked":"139****0002"},{"id":1,"name":"管理员","dept":"商旅分销部","employeeNo":null,"role":"ADMIN","phoneMasked":"138****0000"}]}
```
→ 仍是 2 条 ACTIVE，**不含「李停用」**；SALES 的 `enabled=false` 被强制按 `true` 处理。

**6. `q` 模糊搜索**
```json
# GET /users?q=销售
{"code":0,"message":"ok","data":[{"id":10,"name":"王销售","dept":"销售一部","employeeNo":null,"role":"SALES","phoneMasked":"139****0002"}]}
# GET /users?q=13900000002
{"code":0,"message":"ok","data":[{"id":10,"name":"王销售","dept":"销售一部","employeeNo":null,"role":"SALES","phoneMasked":"139****0002"}]}
```
→ 两组查询均命中王销售。

**7. 响应里不能有密码或明文手机号**
```
PASS: 无泄漏
```

**8. 未登录**
```json
{"code":1002,"message":"未登录或登录已过期","data":null}
```

### 2.4 测试数据清理

```sql
delete from sys_user where phone in ('13900000002','13900000003');
```
清理后 `sys_user` 恢复为 `id=1 管理员 13800000000 ADMIN ACTIVE` 一行（与基线一致）。

## 3. 规则逐条落点

| 规则 | 实现 |
|---|---|
| 默认只返回 ACTIVE | `UserQueryService`：`includeDisabled=false` 时 `status='ACTIVE'` |
| SALES 传 `enabled=false` 仍只返回 ACTIVE | `UserController`：`Boolean.FALSE.equals(enabled) && Role.ADMIN.name().equals(current.role())` 才放开 |
| ADMIN 传 `enabled=false` 返回全部 | 同上，ADMIN 时 `includeDisabled=true` |
| 不返回明文手机号 | `PhoneMasker.mask()` → `phoneMasked` |
| 不返回密码字段 | DTO 无该字段；查询 `select(...)` 白名单不含 `password_hash` |
| 只有本人时返回 `[本人]` | 数据驱动（见验收 #1） |
| 增删改后 60 秒内缓存失效 | 未加缓存，实时查库，天然满足；10 人量级足够 |
| `q` 模糊匹配 | `ILIKE` 大小写不敏感；`%`/`_` 转义按字面量；去首尾空格；超过 40 截断 |
| 排序 | `name` 升序（默认 collation，环境有 `zh-x-ICU` 但未硬编码，避免换库缺 collation 启动/查询失败） |

## 4. 是否修改禁止文件

**否。** `git status` 仅 5 个新增文件（4 个 Java 源文件/测试 + `crm-app/src/api/user.ts`），未触碰 `pom.xml`、`security/**`、`common/**`、并行任务的 `TagController/LeadSourceController/TagService/LeadSourceService`、`request.ts`、`tag.ts`/`leadSource.ts`、`TagSheet.vue`/`SourceSheet.vue`、`pages.json`、`shared/db/**`、`docs/**`、`spec/**`。

## 5. 与文档的差异 / 需要 reviewer 知道的事

1. **`q` 额外匹配手机号**：契约（`spec/08`）写的是匹配 `name` / `employee_no`，但验收步骤 6 用 `q=13900000002`（手机号）并期望命中，因此 `q` 同时匹配 `phone`。出参仍只有脱敏手机号。若要求严格按契约，可删掉 `OR phone ILIKE ...` 一处。
2. **排序**：Issue 规则表写「按 `name` 升序」，`spec/02` BE-API-055 写「`role DESC` → `name ASC`」。本实现按 Issue 的 `name ASC`；验收 #2 的返回即为姓名升序。
3. **`q` 超长**：按契约 maxLength 40 截断前 40 个字符（不报 1001）。
4. **未加 Redis 缓存**：Issue 明确「不缓存也可以」，且缓存失效逻辑会引入并行改动的额外耦合。
5. **环境适配**：本机没有 `psql` 客户端（`which psql` 为空），验收脚本里的 SQL 用等价的 PostgreSQL JDBC 工具执行；`8080` 被并行任务实例占用，验收改用 `18080`。两条都只是验证手段的替代，不改变接口与 SQL 语义。

## 6. 前端

```bash
cd crm-app && npm install && npm run type-check
```

```
> crm-app@1.0.0 type-check
> vue-tsc --noEmit
EXIT=0
```

（`crm-app/src/api/user.ts` 只做请求封装与类型定义，页面/组件未改动，因此无 UI 前后截图。）
