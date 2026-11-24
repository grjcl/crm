# TASK-AUTH-REPORT · 鉴权基础设施 + 登录闭环

- 任务：Issue #8 / Issue 03（BE-SEC-001~004、BE-INFRA-002、BE-API-001~004、FE-PAGE-001）
- 分支：`agent/auth-infra-login-717ee69452da`
- 基线：`main` @ `fee97fe`（工程骨架 PR #7 已合并）
- 环境：Java 21.0.13.1（Temurin）、Maven Wrapper 3.9.9、Node 24.22.3、PostgreSQL 16.16（`192.168.1.53:5432/crm`）、Redis 7（`192.168.1.53:6379`，需要 AUTH）
- 约束遵守：未修改 `shared/db/`、`docs/`、`spec/`；未实现 auth 4 个接口与 `/me` 之外的接口

## 1. 新增与修改文件

### 1.1 新增

| 文件 | 说明 |
|---|---|
| `crm-server/crm-api/src/main/java/com/crm/api/common/ErrorCode.java` | 统一错误码枚举（0/1001/1002/1003/2001/2002/2003/3001/3002/4001/5000） |
| `crm-server/crm-api/src/main/java/com/crm/api/common/BizException.java` | 业务异常，携带业务码与 HTTP 状态 |
| `crm-server/crm-api/src/main/java/com/crm/api/common/PageQuery.java` | 分页入参（默认 1/20，上限 100，越界 1001） |
| `crm-server/crm-api/src/main/java/com/crm/api/common/PageResult.java` | 分页出参（`{list,total,page,size}`，空结果 `[]`） |
| `crm-server/crm-api/src/main/java/com/crm/api/common/PhoneMasker.java` | 手机号脱敏 `13800000000 → 138****0000` |
| `crm-server/crm-api/src/main/java/com/crm/api/security/JwtService.java` | HS256 签发/解析，access 2h / refresh 30d，60s 时钟偏移，UUID jti |
| `crm-server/crm-api/src/main/java/com/crm/api/security/JwtAuthenticationFilter.java` | Bearer 解析、类型/签名/黑名单/账号状态校验，写入请求上下文 |
| `crm-server/crm-api/src/main/java/com/crm/api/security/RequireRole.java` | 角色注解 |
| `crm-server/crm-api/src/main/java/com/crm/api/security/Role.java` | `SALES` / `ADMIN` |
| `crm-server/crm-api/src/main/java/com/crm/api/security/RoleInterceptor.java` | `@RequireRole` 校验，角色不足抛 1003（HTTP 403） |
| `crm-server/crm-api/src/main/java/com/crm/api/security/AuthContext.java` | 当前请求用户上下文（ThreadLocal） |
| `crm-server/crm-api/src/main/java/com/crm/api/security/TokenBlacklistService.java` | Redis 登出黑名单、停用时间戳、refresh jti 索引 |
| `crm-server/crm-api/src/main/java/com/crm/api/service/LoginAttemptService.java` | `login:fail:{phone}` 计数，5 次锁定 15 分钟 |
| `crm-server/crm-api/src/main/java/com/crm/api/service/AuthService.java` | 登录/刷新/登出/当前用户领域逻辑 |
| `crm-server/crm-api/src/main/java/com/crm/api/controller/AuthController.java` | `POST /auth/login`、`/auth/refresh`、`/auth/logout` |
| `crm-server/crm-api/src/main/java/com/crm/api/controller/MeController.java` | `GET /me` |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/LoginRequest.java` | 登录入参 |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/RefreshRequest.java` | 刷新入参 |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/LoginResponse.java` | 登录出参 |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/TokenResponse.java` | 刷新出参 |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/UserSummary.java` | 登录用户摘要（手机号已脱敏） |
| `crm-server/crm-api/src/main/java/com/crm/api/dto/MeResponse.java` | `/me` 出参（`avatarUrl` 恒 null，`lastLoginAt` 带 +08:00） |
| `crm-server/crm-api/src/main/java/com/crm/api/config/WebMvcConfig.java` | 注册角色拦截器、BCryptPasswordEncoder |
| `crm-server/crm-api/src/test/java/com/crm/api/common/PageQueryTest.java` | 分页边界单测（5 条） |
| `crm-server/crm-api/src/test/java/com/crm/api/common/PhoneMaskerTest.java` | 脱敏单测（2 条） |
| `crm-server/crm-api/src/test/java/com/crm/api/security/JwtServiceTest.java` | JWT 签发/解析/篡改单测（4 条） |
| `crm-server/crm-api/src/test/java/com/crm/api/security/RoleInterceptorTest.java` | 角色 1003/HTTP 403 单测与 MockMvc（4 条） |
| `handoff/evidence/login-before.png` | 登录页改造前基线截图（`fee97fe`，390×844@2x） |
| `handoff/evidence/login-after.png` | 登录页改造后截图（本分支，390×844@2x） |
| `handoff/TASK-AUTH-REPORT.md` | 本报告 |

### 1.2 修改

| 文件 | 修改 |
|---|---|
| `crm-server/crm-api/src/main/java/com/crm/api/common/GlobalExceptionHandler.java` | 业务异常按码返回；校验失败 1001；未捕获异常 5000 且不泄露堆栈 |
| `crm-server/crm-api/pom.xml` | 增加 `spring-security-crypto`（BCrypt） |
| `crm-server/crm-api/src/main/resources/application.yml` | Redis 增加 `${REDIS_PASSWORD:123456}`（服务端要求 AUTH） |
| `crm-server/crm-domain/src/main/java/com/crm/domain/entity/SysUser.java` | `timestamptz` 字段改 `OffsetDateTime`（原 `LocalDateTime` 无法映射 PG TIMESTAMPTZ，读取 `sys_user` 直接报错） |
| `crm-app/src/utils/request.ts` | 1002/401 自动 refresh 并重放、业务码抛 `ApiError`、防死循环、清 token 跳登录页 |
| `crm-app/src/pages/login/login.vue` | 按原型实现登录页与登录交互 |
| `crm-app/src/pages.json` | 登录页改为自定义导航（原型无导航栏） |

## 2. 实际执行与原始输出

### 2.1 后端编译

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package && echo "BUILD OK"
```

```text
BUILD OK
EXIT=0
```

### 2.2 启动与 11 步链路（原样粘贴）

启动命令：`java -jar crm-api/target/crm-api-1.0.0-SNAPSHOT.jar`（工作目录 `crm-server`，Flyway 复用 `../shared/db`，日志 `/tmp/api.log`）。

```text
===== 1. health =====
{"code":0,"message":"ok","data":{"status":"UP"}}
===== 2. login =====
{"code":0,"message":"ok","data":{"accessToken":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwicm9sZSI6IkFETUlOIiwidHlwZSI6ImFjY2VzcyIsImp0aSI6Ijc3ZDI1Zjc5LWQxYjktNGNiNy04MmNhLTcyMjY4ODI5YzE2YiIsImlhdCI6MTc5NTUzMjQwNSwiZXhwIjoxNzk1NTM5NjA1fQ.t76OoDXUueGVxz-Typyv8e2x-_ZTlrMD9ZWTZ_ybXoQ","refreshToken":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwicm9sZSI6IkFETUlOIiwidHlwZSI6InJlZnJlc2giLCJqdGkiOiI3YmZkNTgyOS04OWU4LTQxZDUtOTA4Yi04NjMwYzk4YWM1ZjUiLCJpYXQiOjE3OTU1MzI0MDUsImV4cCI6MTc5ODEyNDQwNX0.q6G9xUQQ3bYb7Gee3sFt4rpA-_ZBAq8o0e4fcSJm6zk","expiresIn":7200,"user":{"id":1,"name":"管理员","phone":"138****0000","employeeNo":null,"dept":"商旅分销部","role":"ADMIN"}}}
===== 3. /me with token =====
{"code":0,"message":"ok","data":{"id":1,"name":"管理员","phone":"138****0000","employeeNo":null,"dept":"商旅分销部","role":"ADMIN","avatarUrl":null,"lastLoginAt":"2026-11-24T23:00:05+08:00"}}
===== 4. /me without token =====
{"code":1002,"message":"未登录或登录已过期","data":null}
===== 5. wrong password =====
{"code":2002,"message":"手机号或密码错误","data":null}
===== 6. unknown phone =====
{"code":2001,"message":"手机号未开通","data":null}
===== 7. invalid phone =====
{"code":1001,"message":"请输入正确的手机号","data":null}
===== 8. refresh =====
{"code":0,"message":"ok","data":{"accessToken":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwicm9sZSI6IkFETUlOIiwidHlwZSI6ImFjY2VzcyIsImp0aSI6IjJhZjI4ZDUzLTVhMzYtNDA5My05YjI5LTFjMmQ3ZGU3NGYzMiIsImlhdCI6MTc5NTUzMjQwNSwiZXhwIjoxNzk1NTM5NjA1fQ.wZJk1ioozWN443jpj3aYOmO2DCLQ00JM4F_Y_hGJJwU","refreshToken":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwicm9sZSI6IkFETUlOIiwidHlwZSI6InJlZnJlc2giLCJqdGkiOiI1ZGZlYjEzMy1lZTNjLTRhNWItYWI3Ni1mMzMyMjg3N2M5MDMiLCJpYXQiOjE3OTU1MzI0MDUsImV4cCI6MTc5ODEyNDQwNX0.CENrqUyXnjXqO6h1qETOBwpvJTwGkcJIdCwpCjb-ahk","expiresIn":7200}}
===== 9. logout =====
{"code":0,"message":"ok","data":null}
===== 10. old token after logout =====
{"code":1002,"message":"登录已过期，请重新登录","data":null}
===== 11. five wrong then correct =====
{"code":2003,"message":"密码错误次数过多，请 15 分钟后再试","data":null}
```

逐条核对：

| # | 请求 | 期望 code | 实际 code | 结果 |
|---|---|---|---|---|
| 1 | health | 0 | 0 | ✅ |
| 2 | 正确登录 | 0，含 accessToken/refreshToken | 0，含双 token，`expiresIn=7200` | ✅ |
| 3 | `/me` 带 token | 0，phone 为 `138****0000` | 0，`138****0000`，`lastLoginAt=2026-11-24T23:00:05+08:00` | ✅ |
| 4 | `/me` 无 token | 1002 | 1002 | ✅ |
| 5 | 密码错 | 2002 | 2002 | ✅ |
| 6 | 手机号不存在 | 2001 | 2001 | ✅ |
| 7 | 手机号格式错 | 1001 | 1001 | ✅ |
| 8 | refresh | 0，新 token | 0，返回新 access + refresh | ✅ |
| 9 | 登出 | 0 | 0 | ✅ |
| 10 | 登出后旧 token | 1002 | 1002 | ✅ |
| 11 | 5 次错误后正确密码 | 2003 | 2003 | ✅ |

### 2.3 前端构建与类型检查

```bash
cd crm-app && npm run build && npm run type-check
```

```text
> crm-app@1.0.0 build
> uni build -p h5

Compiler version: 5.38（vue3）
Compiling...
DONE  Build complete.

$ npx vue-tsc --noEmit
EXIT=0
```

### 2.4 安全抽查（密码不落日志）

```bash
grep -ri "admin123\|wrong999" /tmp/api.log && echo "FAIL: 密码泄漏" || echo "PASS: 无密码泄漏"
```

```text
PASS: 无密码泄漏
```

同时统计 `ERROR` 行数：

```text
0
```

> 2026-11-24 22:59 启用的最终服务日志中无 ERROR；WARN 均为业务码日志（如 2001/2002/1001），不含请求体与密码。链路在最终代码重启后复跑。

### 2.5 补充验证（超出 11 步、用于覆盖 AC）

```text
===== 注销后旧 refresh 再刷新（AC-AUTH-06） =====
{"code":1002,"message":"未登录或登录已过期","data":null}

===== 重复登出（幂等） =====
{"code":0,"message":"ok","data":null}

===== 篡改签名 token（AC-INFRA-07） =====
{"code":1002,"message":"未登录或登录已过期","data":null}

===== 用 refresh token 冒充 access =====
{"code":1002,"message":"未登录或登录已过期","data":null}

===== 停用账号登录（AC-AUTH-04/AC-ADMIN-06；临时账号，已删除） =====
{"code":2001,"message":"账号已停用","data":null}

===== 密码策略（AC-AUTH-08） =====
abcdef  -> {"code":1001,"message":"请输入密码","data":null}
123456  -> {"code":1001,"message":"请输入密码","data":null}
abc123  -> {"code":2002,"message":"手机号或密码错误","data":null}   # 格式通过，仅密码错
```

Redis 状态抽查：

```text
TTL login:fail:13800000000 = :845          # 约 15 分钟
GET login:fail:13800000000 = "5"           # 第 5 次失败后锁定
EXISTS login:fail:13900000000 = :0         # 账号不存在不创建计数（防探测）
KEYS blacklist:jti:*                       # access + refresh 的 jti 均存在
```

Redis 不可用降级（另起 8081 实例，`REDIS_PASSWORD=wrongpassword`）：

```text
GET  /api/v1/health           -> {"code":0,...}
POST /api/v1/auth/login       -> {"code":0,...accessToken/refreshToken...}
GET  /api/v1/me (带 token)    -> {"code":0,"data":{"phone":"138****0000",...}}
日志 "Redis 不可用" 计数       -> 8 条（error 日志，业务未阻断）
```

审计表（`audit_log`）：

```text
3 | LOGOUT | 1 | sys_user | 1
2 | LOGIN  | 1 | sys_user | 1
1 | LOGIN  | 1 | sys_user | 1
```

单元测试（`./mvnw -q test`，exit=0）：

```text
PageQueryTest         Tests run: 5, Failures: 0, Errors: 0
PhoneMaskerTest       Tests run: 2, Failures: 0, Errors: 0
JwtServiceTest        Tests run: 4, Failures: 0, Errors: 0
RoleInterceptorTest   Tests run: 4, Failures: 0, Errors: 0
合计                  15 条全部通过
```

浏览器端到端（H5 构建产物 + 真实后端，仅在验证时关闭浏览器同源策略）：

```text
invalid phone toast: true
invalid phone requests: 0
wrong password toast: true
redirect url: http://127.0.0.1:4173/#/pages/partners/partners
tokens stored: true true accessLen: 227
errors: []
```

## 3. 与 PRD / spec 的偏差

以下偏差均由 Issue #8 的显式规则覆盖 PRD/spec 旧口径，按 Issue 执行：

1. **账号不存在与密码错误的提示**：PRD §5.1-3 / AC-AUTH-02 要求统一提示「手机号或密码错误」；Issue 错误码表要求账号不存在返回 `2001`、密码错返回 `2002`。实现按 Issue：不存在返回 2001「手机号未开通」，密码错返回 2002「手机号或密码错误」。计数 key 仍只在「账号存在且密码错」时创建，避免探测。
2. **锁定触发次数**：AC-AUTH-03 写「第 6 次返回 2003」，Issue 写「第 5 次失败 → 2003」。实现按 Issue：第 5 次失败即计入锁定；第 6 次（即使密码正确）返回 2003。
3. **登录密码强度**：R-AUTH-05 写「登录只做长度兜底」；Issue 要求登录时校验 6–20 位且同时含字母与数字，否则 1001「请输入密码」。实现按 Issue。
4. **`/me` 出参**：Issue 示例未列 `avatarUrl`，spec 02 / AC-USER-02 要求固定返回 `avatarUrl: null`。实现包含 `avatarUrl: null`，其余字段与 Issue 一致。
5. **黑名单 key 命名**：spec 02 写 `jwt:black:{jti}` / `jwt:disabled:{userId}`；Issue 写 `blacklist:jti:{jti}` / `blacklist:user:{userId}`。实现按 Issue。
6. **登出双 jti**：登出接口无入参，无法从 access token 推出 refresh jti。实现为：登录/刷新时把 refresh jti 记录到 Redis `refresh:user:{userId}`（hash：jti → 过期毫秒），登出时把 access jti 与该用户全部 refresh jti 写入黑名单并删除索引。重复登出由过滤器识别已黑名单 access token 并返回 `code:0`。
7. **Redis 认证**：环境 Redis 开启 AUTH，`application.yml` 增加 `${REDIS_PASSWORD:123456}`；不配置则登录计数/黑名单全部降级，锁定测试无法通过。
8. **`SysUser` 时间字段类型**：原骨架 `LocalDateTime` 与 PostgreSQL `TIMESTAMPTZ` 不兼容（读取 `created_at` 即抛 `Cannot convert the column of type TIMESTAMPTZ`）。改为 `OffsetDateTime`，属于让 `sys_user` 可读的必要修复。

## 4. 未完成项与验证边界

**按 Issue 4.2/4.3/4.4 的验收项：无未完成项，11 条全部通过。**

以下为本 Ticket 范围导致的覆盖边界，不是功能缺失：

- 本 Ticket 没有 ADMIN 专属接口，`@RequireRole` 的 1003 / HTTP 403 通过 `RoleInterceptorTest`（含 MockMvc 断言 HTTP 403 + `code:1003`）覆盖；没有用业务接口做端到端调用。
- 本 Ticket 没有分页列表接口，`PageQuery` / `PageResult` 的 1001 边界与空数组通过单测覆盖。
- H5 预览页跨域访问 `http://localhost:8080` 需要开发代理或后端 CORS；本 Ticket 未修改后端 CORS（不在任务范围），移动端与同源部署不受影响。浏览器端到端验证时仅在测试浏览器关闭同源策略，未修改应用代码。
- 只执行了 H5 构建（Issue 4.3 要求），未执行 iOS/Android 原生打包。
- 未实现「管理员停用账号」接口（不在本 Ticket 范围）；停用即时失效已由过滤器每次请求校验 `sys_user.status` + 读取 `blacklist:user:{userId}` 实现并验证。

## 5. 视觉证据

- 改造前（`main` @ `fee97fe`）：`handoff/evidence/login-before.png`
- 改造后（本分支）：`handoff/evidence/login-after.png`
- 场景：登录页默认态、390×844 视口、2x 像素密度、浅色主题；无预填账号密码。
- 改造后 DOM 实测包含：Logo「商」、标题「商旅分销 CRM」、副标题、手机号、密码（可切换显示）、「登 录」、「忘记密码？」「未开通账号请联系管理员」、底部两行说明；无控制台错误。
