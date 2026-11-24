# Issue 02：工程骨架（后端 Spring Boot + 前端 uni-app）

**目标**：在现有仓库里搭出**可编译、可启动**的前后端工程骨架。本任务不实现任何业务逻辑，只建立地基。

**输入文档（在仓库里读）**：
- `CLAUDE.md` ← 环境约定（**必读**，含 JAVA_HOME / Maven Wrapper / 数据库连接）
- `docs/CRM技术选型方案.md` §6.1 ← 目录结构
- `docs/CRM技术选型方案.md` §7 ← 版本锁定清单
- `spec/03-前端页面与交互.md` §0.1 ← 10 个屏幕清单
- `shared/db/*.sql` ← 已建好的 16 张表（实体类要对应）

---

## 一、Python 环境（先说清楚）

**这个容器没有 Maven，也不需要装。** 用 Maven Wrapper：

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
```

`mvnw` 首次执行会自动下载 Maven 到 `~/.m2/wrapper/`（约 8.7MB，已实测可下载）。

---

## 二、后端：`crm-server/`

### 2.1 父 POM `crm-server/pom.xml`

严格按 `docs/CRM技术选型方案.md` §7 的版本：

```xml
<java.version>21</java.version>
<spring-boot.version>3.3.x</spring-boot.version>
<mybatis-plus.version>3.5.x</mybatis-plus.version>
<flyway.version>10.x</flyway.version>
<jjwt.version>0.12.x</jjwt.version>
<springdoc.version>2.x</springdoc.version>
```

模块：

```xml
<modules>
  <module>crm-domain</module>
  <module>crm-infra</module>
  <module>crm-api</module>
  <module>crm-worker</module>
</modules>
```

依赖至少包含：

| 依赖 | 用途 |
|---|---|
| `spring-boot-starter-web` | MVC |
| `spring-boot-starter-validation` | Jakarta Validation |
| `spring-boot-starter-data-redis` | Redis |
| `mybatis-plus-spring-boot3-starter` | ORM |
| `flyway-core` + `flyway-database-postgresql` | 迁移 |
| `postgresql` | JDBC 驱动 |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | JWT |
| `springdoc-openapi-starter-webmvc-ui` | OpenAPI |
| `spring-boot-starter-test` | 测试 |

### 2.2 `crm-domain/`

**16 个实体类**，与 `shared/db/V1__init.sql` 一一对应：

```
SysUser, Org, OrgInvoice, OrgTag, OrgTagRel, OrgSupply,
Contact, Lead, LeadSource, LeadPool, Deal, Follow,
FollowPlan, Channel, Attachment, AuditLog
```

要求：
- MyBatis-Plus `@TableName("sys_user")` 等注解
- 字段名与表字段一一对应（`@TableField` 处理驼峰/下划线，默认即可）
- 只加 `@Data`，**不写任何业务方法**
- `Org` 的 `productLines` 和 `OrgSupply` 的 `supplierType`/`coopMode` 用 `String`（JSONB 在 MyBatis-Plus 里可以先用 String，后面再配 TypeHandler）
- `OrgTagRel` 是复合主键，用 `@TableId` 单独处理或先建实体不启用

### 2.3 `crm-infra/`

每个实体一个 Mapper 接口，继承 `BaseMapper<T>`，**空的**：

```java
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {}
```

另外提供：
- `application.yml` 里的数据源配置（见 2.5）
- `MybatisPlusConfig`：分页插件
- 一个 `RedisConfig`（`StringRedisTemplate` 就够，不用自定义序列化器）

### 2.4 `crm-api/`

必须包含：

1. **`CrmApiApplication.java`**
```java
@SpringBootApplication
@MapperScan("com.crm.infra.mapper")
public class CrmApiApplication {
    public static void main(String[] args) { SpringApplication.run(CrmApiApplication.class, args); }
}
```

2. **`ApiResponse<T>`**（`spec/05-开发任务清单.md` BE-INFRA-001）
```java
{ code: 0, message: "ok", data: ... }
```
提供静态工厂 `ok(data)` / `ok()` / `error(code, message)`。

3. **`GlobalExceptionHandler`**（`@RestControllerAdvice`）
- `MethodArgumentNotValidException` → `code=400` + 字段错误列表
- 其他异常 → `code=500`

4. **健康检查** `GET /api/v1/health`
```json
{"code":0,"message":"ok","data":{"status":"UP"}}
```

5. **`application.yml`**
```yaml
server:
  port: 8080
spring:
  datasource:
    url: jdbc:postgresql://192.168.1.53:5432/crm
    username: root
    password: 123456
  flyway:
    enabled: true
    locations: filesystem:../shared/db    # 复用已建好的 SQL
    baseline-on-migrate: true
  data:
    redis:
      host: 192.168.1.53
      port: 6379
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
```

**Flyway 注意**：`shared/db/` 里已有 `V1__init.sql`、`V2__seed.sql`、`V3__admin_seed.sql`，名字符合 Flyway 规范，**直接复用，不要重写**。但数据库里这些表已经建好了，所以要设 `baseline-on-migrate: true`，或者建一个 `baselineVersion: 3`。

6. **JWT 配置占位**
```yaml
crm:
  jwt:
    secret: ${JWT_SECRET:crm-dev-secret-key-change-in-production-min-32-chars}
    access-ttl: 2h
    refresh-ttl: 30d
```

### 2.5 `crm-worker/`

只放一个 `pom.xml` 和一个空的 `CrmWorkerApplication`（`@SpringBootApplication` 但**不启动**，加 `spring.autoconfigure.exclude` 或直接不引 web 依赖）。

### 2.6 Maven Wrapper

在 `crm-server/` 下生成 `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`、`.mvn/wrapper/maven-wrapper.jar`。

**获取方式**（已实测可下载，HTTP 200 / 7755B）：

```bash
cd crm-server
curl -fsSL -o /tmp/wrapper.zip   https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.2/maven-wrapper-distribution-3.3.2-only-script.zip
unzip -o /tmp/wrapper.zip -d /tmp/wrapper-dist
# 该包内是 mvnw / mvnw.cmd 脚本，复制到 crm-server/ 并 chmod +x mvnw
```

然后创建 `.mvn/wrapper/maven-wrapper.properties`：

```properties
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
wrapperUrl=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar
```

**不需要 `maven-wrapper.jar`**：wrapper 3.3.x 的脚本会在首次运行时自动下载它。执行 `./mvnw -v` 就能看到它自己把 Maven 拉下来。

如果 `unzip` 不可用，用 `python3 -c "import zipfile;zipfile.ZipFile('/tmp/wrapper.zip').extractall('/tmp/wrapper-dist')"`。

---

## 三、前端：`crm-app/`

### 3.1 初始化

uni-app Vue3 + TS 工程（**CLI 方式，不用 HBuilderX**）。

依赖版本按 `docs/CRM技术选型方案.md` §7：
```json
"vue": "^3.5.0",
"@dcloudio/uni-app": "^3.0.0",
"pinia": "^2.1.0",
"wot-design-uni": "^1.3.0",
"luch-request": "^3.0.0",
"dayjs": "^1.11.0"
```

devDeps：`vite@^5`、`@dcloudio/vite-plugin-uni`、`typescript@^5`、`vue-tsc`

### 3.2 `pages.json` — 10 个页面

严格按 `spec/03-前端页面与交互.md` §0.1：

| key | 路径 | 是否 Tab |
|---|---|---|
| `login` | `pages/login/login` | 否 |
| `partners` | `pages/partners/partners` | **Tab 1** |
| `company` | `pages/company/company` | 否 |
| `companyNew` | `pages/company/companyNew` | 否 |
| `discover` | `pages/discover/discover` | **Tab 2** |
| `channels` | `pages/channels/channels` | 否 |
| `deal` | `pages/deal/deal` | 否 |
| `follow` | `pages/follow/follow` | 否 |
| `todo` | `pages/todo/todo` | 否 |
| `mine` | `pages/mine/mine` | **Tab 3** |

**三个 Tab**：合作商 / 发现 / 我的。

`globalStyle` 的导航栏颜色用 PRD §4.1 的 `#1F3864`。

### 3.3 目录结构

```
crm-app/
├── src/
│   ├── pages/          # 10 个页面（每个只放一个居中文字，如"合作商"）
│   ├── components/     # 空，留 .gitkeep
│   ├── store/          # 空
│   ├── api/            # 空
│   ├── types/          # 空
│   ├── utils/
│   │   ├── request.ts  # luch-request 封装
│   │   └── date.ts     # dayjs 封装
│   ├── static/
│   ├── App.vue
│   ├── main.ts
│   └── manifest.json
├── pages.json
├── vite.config.ts
├── tsconfig.json
└── package.json
```

### 3.4 `src/utils/request.ts`

```ts
// 要求：
// - baseURL 从 import.meta.env.VITE_API_BASE 读，默认 http://localhost:8080/api/v1
// - 请求拦截：注入 Authorization: Bearer {token}（token 从 uni.getStorageSync('accessToken')）
// - 请求拦截：POST/PUT/DELETE 注入 Idempotency-Key（uuid）
// - 响应拦截：res.data.code !== 0 → 抛错，message 用 res.data.message
// - 401 → 清 token + uni.reLaunch 到登录页
```

### 3.5 `src/utils/date.ts`

按 PRD §4.3 第 4 条实现三个函数：

```ts
formatList(date)    // → "MM-DD"
formatDetail(date)  // → "YYYY-MM-DD"
relativeDay(date)   // → "今天" / "明天" / "N 天后" / "逾期 N 天"
```

`relativeDay` 的边界：跨年、闰日、时区统一 +08:00。

### 3.6 占位页面

每个页面只写：

```vue
<template>
  <view class="page"><text>合作商</text></view>
</template>
```

**不实现任何 UI**。UI 由后续任务按 `docs/crm-phase1-prototype.html` 实现。

---

## 四、硬性要求

| 要求 | 说明 |
|---|---|
| 后端必须能启动 | `./mvnw spring-boot:run` 能起来，`/api/v1/health` 返回 200 |
| 前端必须能构建 | `npm run build` 退出码 0 |
| 不改 `shared/db/*.sql` | 已建好的 16 张表，只能读 |
| 不改 `docs/`、`spec/` | 那是输入 |
| 不实现业务逻辑 | 除了 `/health`，不写任何 controller |
| 版本锁定 | 不得擅自升级依赖 |
| 实体类与表一致 | 16 个实体，字段名必须和 SQL 对应 |

---

## 五、验收（必须实际执行）

### 5.1 后端

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server

# 1. 编译（首次会下载 Maven + 依赖，约 3-5 分钟）
./mvnw -q -DskipTests package
echo "编译退出码: $?"

# 2. 启动测试
./mvnw -q spring-boot:run -pl crm-api > /tmp/api.log 2>&1 &
sleep 45

# 3. 健康检查
curl -s http://localhost:8080/api/v1/health
# 期望：{"code":0,"message":"ok","data":{"status":"UP"}}

# 4. 停掉
pkill -f "spring-boot:run" || true
```

### 5.2 前端

```bash
cd crm-app
npm install
npm run build
echo "构建退出码: $?"
```

### 5.3 结构检查

```bash
# 实体类数量
find crm-server/crm-domain -name "*.java" | wc -l     # 期望 16

# Mapper 数量
find crm-server/crm-infra -name "*Mapper.java" | wc -l  # 期望 16

# 页面数量
python3 -c "import json,re,sys; s=open('crm-app/pages.json').read(); print('pages:', len(re.findall(r'\"path\"', s)))"  # 期望 10

# 三个 Tab
grep -c "tabBar" crm-app/pages.json    # 期望 1
```

---

## 六、完成后

写 `handoff/TASK-SCAFFOLD-REPORT.md`：

1. 目录结构树（两行深度即可）
2. 四个验收命令的**实际输出**
3. 与 `docs/CRM技术选型方案.md` §6.1 的偏差
4. 没做到的（例如某个依赖下载失败），明确写"未完成"及原因

**如果 Maven 或 npm 下载失败**，明确报告错误，不要假装通过。
