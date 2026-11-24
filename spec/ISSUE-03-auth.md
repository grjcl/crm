# Issue 03：鉴权基础设施 + 登录闭环

**目标**：把「登录 → 拿 token → 访问受保护接口」这条链路打通。做完这一批，S0 门禁就过了，后续可以前后端并行开发业务模块。

**前置**：`crm-server/` 骨架已存在并能编译（PR #7 已合并到 main）。

**输入文档（在仓库里读）**：
- `CLAUDE.md` ← 环境约定
- `docs/PRD_一期_v1.3.md` §5.1（登录页）、§5.3（登录状态）、§13.4（错误码）、§14（权限矩阵）
- `spec/05-开发任务清单.md` ← 任务 BE-SEC-001~004、BE-INFRA-002、BE-API-001~004
- `spec/05-附录-验收标准全表.md` ← AC-AUTH-01~07、AC-INFRA-02/07/08
- `shared/db/V1__init.sql` ← `sys_user` 表结构

---

## 错误码（必须严格使用）

| code | 含义 | 何时返回 |
|---|---|---|
| 0 | 成功 | — |
| 1001 | 参数校验失败 | 入参格式不对 |
| 1002 | 未登录 / token 失效 | 无 token、签名不符、过期、已登出 |
| 1003 | 无权限 | 角色不足 |
| 2001 | 手机号未开通 | 账号不存在或已停用 |
| 2002 | 手机号或密码错误 | 密码错（**不区分账号是否存在**） |
| 2003 | 密码错误次数过多 | 锁定期间 |
| 5000 | 服务端异常 | — |

**关键安全要求**：登录失败时，账号不存在返回 `2001`，密码错误返回 `2002`——但**错误次数计数不因账号不存在而创建**（防探测）。

---

## 一、后端任务（8 个）

### 1. `BE-SEC-001` JWT 签发与校验

**产出**：
- `JwtService`：签发 access/refresh，解析与校验
- `JwtAuthenticationFilter`（`OncePerRequestFilter`）

**规则**：
- 算法 HS256，密钥从 `crm.jwt.secret` 读（已配好在 `application.yml`）
- access TTL 2h，refresh TTL 30d
- 用 `jjwt 0.12.x`
- **时钟偏移容忍 60 秒**（`JwtParserBuilder.clockSkewSeconds(60)`）
- 每次签发 `jti` 唯一（UUID）
- 签名不符或过期 → `1002`

**payload 至少包含**：`sub`(userId) / `role` / `jti` / `iat` / `exp` / `type`(access|refresh)

### 2. `BE-SEC-002` 角色注解

**产出**：
- `@RequireRole({SALES, ADMIN})` 注解
- 拦截器或 AOP 实现

**规则**：
- SALES 访问 ADMIN 接口 → HTTP 403 + `code: 1003`
- **不加注解 = 放行**（login / refresh / health / version 走这条）
- `GET /me` 需要登录但不需要特定角色

### 3. `BE-SEC-003` 登出黑名单

**产出**：`TokenBlacklistService`（Redis）

**规则**：
- key 格式 `blacklist:jti:{jti}`，TTL = token 剩余有效期
- 校验 token 时先查黑名单，命中 → `1002`
- 账号停用后**所有**未过期 token 失效：key `blacklist:user:{userId}`，值为停用时间戳
- **Redis 不可用时降级放行**并记 error 日志（不阻断业务）

### 4. `BE-SEC-004` 登录失败计数

**产出**：`LoginAttemptService`（Redis）

**规则**：
- key `login:fail:{phone}`，TTL 15 分钟
- 第 5 次失败 → 返回 `2003`，锁定 15 分钟
- 锁定期间**即使密码正确也返回 2003**
- 登录成功后 `DEL` 计数
- **账号不存在时不创建计数 key**（防探测：否则可以靠"有没有被锁"判断账号是否存在）
  - 实现方式：只在"账号存在且密码错"时才 `INCR`

### 5. `BE-INFRA-002` 分页封装

**产出**：`PageQuery` / `PageResult<T>`

**规则**：
- `?page=1&size=20`
- `size > 100` 或 `page < 1` → `1001`
- `size` 缺省补 20
- `total = 0` 时 `list` 返回空数组 `[]`，**不是 null**

### 6. `BE-API-001` `POST /api/v1/auth/login`

**入参**：
```json
{ "phone": "13800000000", "password": "admin123" }
```

**校验**：
- `phone` 匹配 `^1[3-9]\d{9}$`，不匹配 → `1001`「请输入正确的手机号」
- `password` 6–20 位，必须同时含字母和数字，不匹配 → `1001`「请输入密码」

**逻辑**：
1. 查 `sys_user` by phone
2. 不存在 → `2001`
3. `status != 'ACTIVE'` → `2001`「账号已停用」
4. 查锁定 → 锁定中返回 `2003`
5. BCrypt 校验密码
   - 错 → 计数 +1，返回 `2002`
   - 对 → 清零计数，签发双 token，更新 `last_login_at`

**出参**：
```json
{
  "code": 0, "message": "ok",
  "data": {
    "accessToken": "...", "refreshToken": "...", "expiresIn": 7200,
    "user": { "id": 1, "name": "管理员", "phone": "138****0000", "role": "ADMIN", "dept": "商旅分销部" }
  }
}
```

### 7. `BE-API-002` `POST /api/v1/auth/refresh`

**入参**：`{ "refreshToken": "..." }`

**逻辑**：
- 校验 refresh token（type 必须是 refresh）
- 查黑名单
- 用户已停用 → `2001`
- 签发新的 access + refresh（**本期不轮换 refresh**，同一个 refresh 30 天内可多次换发）

**出参**：同 login 的 `accessToken` / `refreshToken` / `expiresIn`

### 8. `BE-API-003` `POST /api/v1/auth/logout`

**逻辑**：
- 不需入参，从当前 token 取 jti
- 把 access 的 jti 和 refresh 的 jti **都**塞进黑名单
- 写审计 `audit_log`（action=`LOGOUT`）
- 重复调用仍返回 `code: 0`

### 9. `BE-API-004` `GET /api/v1/me`

**规则**：
- 需要登录（无 `@RequireRole`，但需 JWT 有效）
- 用户已停用 → `1002`

**出参**：
```json
{ "id": 1, "name": "管理员", "phone": "138****0000",
  "employeeNo": null, "dept": "商旅分销部", "role": "ADMIN", "lastLoginAt": "2026-11-24T10:00:00+08:00" }
```

**手机号必须脱敏**：`13800000000` → `138****0000`

---

## 二、前端任务（2 个）

### 1. 扩展 `crm-app/src/utils/request.ts`

已有的拦截器需补：

- **401 处理**：清 token → `uni.reLaunch({ url: '/pages/login/login' })`
- **业务码映射**：`code != 0` 时抛出自定义错误，带 `code` 和 `message`
- **refresh 自动续期**：401 时若有 refreshToken，先调 `/auth/refresh`，成功则重放原请求；失败才跳登录页
  - **注意避免死循环**：refresh 请求本身失败时不能再触发 refresh

### 2. `FE-PAGE-001` 登录页 `crm-app/src/pages/login/login.vue`

**UI 基线**：`docs/crm-phase1-prototype.html` 的 `login` 屏（搜 `SCREENS.login`）。

**必须一致的要素**（照抄原型）：
- Logo + 标题「商旅分销 CRM」+ 副标题
- 手机号输入（数字键盘）
- 密码输入（`password` 类型，可切换显示）
- 「登录」按钮
- 一行小字：「忘记密码？」「未开通账号请联系管理员」
- 底部说明：「本期仅支持手机号 + 密码登录 / 密码错误 5 次锁定 15 分钟」

**交互**：
- 前端先校验手机号格式，不通过直接 toast「请输入正确的手机号」，不发请求
- 登录成功 → 存 `accessToken` / `refreshToken` 到 storage → `uni.reLaunch` 到合作商列表
- 失败 → toast `message`（1002/2001/2002/2003 的文案见下方）
- 点「忘记密码？」→ toast「请联系管理员重置密码」

**错误文案**：
| code | toast |
|---|---|
| 1001 | 请输入正确的手机号 / 请输入密码 |
| 2001 | 手机号未开通，请联系管理员 |
| 2002 | 手机号或密码错误 |
| 2003 | 密码错误次数过多，请 15 分钟后再试或联系管理员 |

**已登录自动跳转**：`onLoad` 时检查 storage 有无有效 `refreshToken`，有则直接 `reLaunch` 到合作商列表。

---

## 三、硬性要求

| 要求 | 说明 |
|---|---|
| 不改 `shared/db/` | 表结构已定 |
| 不改 `docs/`、`spec/` | 输入文档 |
| 不实现其他模块的接口 | 只做 auth 4 个 + me |
| 错误码严格按表 | 不许自创码 |
| 手机号脱敏 | `138****0000` 格式 |
| 密码不落日志 | 任何日志里都不能出现 password |
| 不用 `System.out` | 用 SLF4J |

**测试账号**（已在 `V3__admin_seed.sql`）：
```
手机号：13800000000
密码：admin123
角色：ADMIN
```
BCrypt hash 已入库，直接用。

---

## 四、验收（必须实际执行，粘贴原始输出）

### 4.1 编译

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package && echo "BUILD OK"
```

### 4.2 启动并测试完整链路

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server
./mvnw -q spring-boot:run -pl crm-api > /tmp/api.log 2>&1 &
sleep 50

BASE=http://localhost:8080/api/v1

# ---- 1. health ----
curl -s $BASE/health

# ---- 2. 登录成功 ----
LOGIN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"admin123"}')
echo "$LOGIN"
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
REFRESH=$(echo "$LOGIN" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['refreshToken'])")

# ---- 3. GET /me 带 token ----
curl -s $BASE/me -H "Authorization: Bearer $TOKEN"

# ---- 4. GET /me 不带 token → 1002 ----
curl -s $BASE/me

# ---- 5. 密码错误 → 2002 ----
curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"wrong123"}'

# ---- 6. 手机号不存在 → 2001 ----
curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13900000000","password":"admin123"}'

# ---- 7. 手机号格式错 → 1001 ----
curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"123","password":"admin123"}'

# ---- 8. refresh ----
curl -s -X POST $BASE/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"

# ---- 9. 登出 ----
curl -s -X POST $BASE/auth/logout -H "Authorization: Bearer $TOKEN"

# ---- 10. 登出后旧 token 再用 → 1002 ----
curl -s $BASE/me -H "Authorization: Bearer $TOKEN"

# ---- 11. 连续 5 次密码错 → 2003 ----
for i in 1 2 3 4 5; do
  curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
    -d '{"phone":"13800000000","password":"wrong999"}' > /dev/null
done
curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"admin123"}'
# 期望：2003（即使密码正确也被锁）

pkill -f "spring-boot:run" || true
```

**预期结果对照表**（必须逐条核对并把实际输出粘进报告）：

| # | 请求 | 期望 code |
|---|---|---|
| 1 | health | 0 |
| 2 | 正确登录 | 0，含 accessToken/refreshToken |
| 3 | /me 带 token | 0，phone 是 `138****0000` |
| 4 | /me 无 token | 1002 |
| 5 | 密码错 | 2002 |
| 6 | 手机号不存在 | 2001 |
| 7 | 手机号格式错 | 1001 |
| 8 | refresh | 0，新 token |
| 9 | 登出 | 0 |
| 10 | 登出后旧 token | 1002 |
| 11 | 5 次错误后正确密码 | 2003 |

### 4.3 前端构建

```bash
cd crm-app && npm run build && echo "FRONTEND OK"
```

### 4.4 安全抽查

```bash
# 日志里不能出现密码明文
grep -ri "admin123\|wrong999" /tmp/api.log && echo "FAIL: 密码泄漏" || echo "PASS: 无密码泄漏"
```

---

## 五、完成后

写 `handoff/TASK-AUTH-REPORT.md`：

1. 新增文件清单
2. **4.2 那张表的逐条实际输出**（11 条，一条不落）
3. 与 PRD 的偏差
4. 未完成项及原因

**如果没有真实执行 4.2 就声称通过，视为任务失败。** 11 条输出缺一条都算不完整。
