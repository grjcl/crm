# TASK-SCAFFOLD-REPORT · 工程骨架（Spring Boot 四模块 + uni-app）

- 任务：Issue #6 / Issue 02 工程骨架
- 分支：`agent/scaffold-crm-app-ea32a4786b51`
- 环境：Java 21.0.13.1（Temurin，`/home/dsh/.local/opt/jdk-21`）、Maven Wrapper 3.3.2 → Maven 3.9.9、Node 24.22.3、npm 11.19.1、PostgreSQL 16.16
- 范围：只搭可编译、可启动的骨架，不实现业务；未修改 `shared/db/`、`docs/`、`spec/`

## 1. 目录结构（两行深度）

```text
crm-server/
├── pom.xml
├── mvnw / mvnw.cmd / .mvn/wrapper/maven-wrapper.properties
├── crm-domain/  pom.xml + src/main/java/com/crm/domain/entity/*.java（16 个实体）
├── crm-infra/   pom.xml + src/main/java/com/crm/infra/mapper/*.java（16 个 Mapper）
│                + src/main/java/com/crm/infra/config/{MybatisPlusConfig,RedisConfig}.java
├── crm-api/     pom.xml + CrmApiApplication、common/ApiResponse、common/GlobalExceptionHandler、
│                controller/HealthController、resources/application.yml
└── crm-worker/  pom.xml + src/main/java/com/crm/worker/CrmWorkerApplication.java

crm-app/
├── package.json / package-lock.json / vite.config.ts / tsconfig.json / index.html
├── pages.json -> src/pages.json
└── src/
    ├── App.vue / main.ts / manifest.json / pages.json / env.d.ts
    ├── pages/（login、partners、company、companyNew、discover、channels、deal、follow、todo、mine）
    ├── components/ store/ api/ types/ static/（空目录，含 .gitkeep）
    └── utils/request.ts、utils/date.ts
```

## 2. 实际执行与输出

### 2.1 后端编译

```text
$ export JAVA_HOME=/home/dsh/.local/opt/jdk-21
$ ./mvnw -v
Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)
Java version: 21.0.13.1, vendor: Eclipse Adoptium

$ ./mvnw -q -DskipTests package
（无输出）
exit=0
```

另用干净本地仓库（`-Dmaven.repo.local=/tmp/m2-clean`）跑过同一条 `package` 命令，exit=0。首次依赖下载使用了临时 `/tmp/maven-settings.xml` 指向阿里云镜像加速；仓库内没有加入镜像配置，构建命令本身不变。

### 2.2 启动与 `/api/v1/health`

**注意**：`spring-boot:run -pl crm-api` 只把 `crm-api` 放入 reactor，不会自动构建兄弟模块。首次启动前需要先 `install` 一次；否则会报 `Could not find artifact com.crm:crm-infra:jar:1.0.0-SNAPSHOT`（已在干净本地仓库复现）。

```text
$ ./mvnw -q -DskipTests install
exit=0

$ ./mvnw -q spring-boot:run -pl crm-api
...
Flyway ... Successfully validated 4 migrations
Flyway ... Current version of schema "public": 3
Flyway ... Schema "public" is up to date. No migration necessary.
Tomcat started on port 8080 (http) with context path '/'
Started CrmApiApplication in 8.23 seconds
```

```text
$ curl -s http://localhost:8080/api/v1/health
HTTP 200
{"code":0,"message":"ok","data":{"status":"UP"}}
```

Flyway 已把 `shared/db` 的 V1/V2/V3 纳入管理：基线版本 1，V2/V3 已记录为成功；没有重写或复制 `shared/db/*.sql`。首次基线时日志出现 `there is already a transaction in progress` 警告，原因是 V2/V3 脚本自带 `BEGIN/COMMIT`，Flyway 外层也有事务；迁移最终成功，种子数据未变化。数据库现有 17 张基础表：16 张业务表 + `flyway_schema_history`。

### 2.3 前端安装、构建与类型检查

```text
$ cd crm-app
$ npm install
exit=0
（npm 11 提示 4 个 install scripts 未授权：esbuild / core-js / core-js-pure / vue-demi；
  平台 esbuild 包已正常安装，构建与类型检查均通过）

$ npm run build
> uni build -p h5
Compiler version: 5.38（vue3）
Compiling...
DONE  Build complete.
exit=0

$ npm run type-check
> vue-tsc --noEmit
exit=0
```

`src/manifest.json` 中已显式关闭 uni 统计（`uniStatistics.enable=false`），构建日志不再出现"已开启 uni统计"。

构建产物在 `crm-app/dist/build/h5/`，包含 `index.html`、主包与 10 个页面 chunk。

### 2.4 结构检查（issue §5.3）

```text
$ find crm-server/crm-domain -name "*.java" | wc -l
16

$ find crm-server/crm-infra -name "*Mapper.java" | wc -l
16

$ python3 -c "import json,re,sys; s=open('crm-app/pages.json').read(); print('pages:', len(re.findall(r'\"path\"', s)))"
pages: 10

$ grep -c "tabBar" crm-app/pages.json
1
```

tabBar 三项：`合作商` / `发现` / `我的`；10 个页面文件均存在，`pages.json` 中无重复路径。

### 2.5 实体与数据库一致性 / 种子数据

用 JDBC 读取 `information_schema.columns`，与 16 个实体类的 `@TableName` / 字段逐列比对：

```text
tables: 16  entities: 16
total fields: 182
OK: every DB column has a matching Java field and type
```

- `bigserial/bigint → Long`、`int/smallint → Integer`、`boolean → Boolean`、`date → LocalDate`、`timestamptz → LocalDateTime`、`numeric → BigDecimal`、`varchar/text/jsonb/array → String`。
- Mapper 与实体一一对应：16 / 16，无缺失、无多余。
- `org_tag` 3 条、`lead_source` 7 条、管理员 `13800000000` 角色 `ADMIN` 均保持原值。

### 2.6 `date.ts` 验证

用实际编译后的 `date.js` 跑了以下用例：

```text
formatList 2024-02-29: "02-29" PASS
formatDetail 2024-02-29: "2024-02-29" PASS
formatDetail 2024-02-29T16:30:00Z: "2024-03-01" PASS（UTC 跨到 +08:00 次日）
relativeDay 今天: "今天" PASS
relativeDay 明天: "明天" PASS
relativeDay +3 天: "3 天后" PASS
relativeDay -2 天: "逾期 2 天" PASS
relativeDay 2028-02-29: "462 天后" PASS（与实际日历差一致）
```

实现统一用 `utcOffset(8 * 60)` 和 `startOf('day')`，纯日期按 `+08:00` 的 00:00 解析，避免浏览器本地时区导致跨日。

## 3. 与 `docs/CRM技术选型方案.md` §6.1 的偏差

| # | 偏差 | 原因 |
|---|---|---|
| 1 | `crm-app/` 增加 `index.html`、`src/env.d.ts` | uni-app CLI/Vite 构建必需；§6.1 只列了业务目录 |
| 2 | `pages.json` 以 `src/pages.json` 为准，仓库根 `crm-app/pages.json` 是指向它的符号链接 | uni-app CLI 只读 `src/pages.json`；issue §5.3 的验收命令读根路径。链接保证两处内容一致，避免复制漂移 |
| 3 | `@dcloudio/uni-app` 等 DCloud 包使用精确版本 `3.0.0-alpha-5030820261124002`，而不是 `^3.0.0` | npm 上没有稳定的 `3.0.0`，`^3.0.0` 无法匹配 `3.0.0-alpha-*`；该版本是当前 `vue3` dist-tag，仍属 3.x |
| 4 | `Org.productLines`、`OrgSupply.supplierType/coopMode` 用 `String`；`Org.collaboratorIds`、`Follow.photos`、`AuditLog.diff` 也用 `String` | 前三个按 issue 要求；后三个是 `bigint[]` / `jsonb`，骨架阶段同样先用 `String`，避免引入 TypeHandler |
| 5 | 父 POM 用 `dependencyManagement` 统一版本，运行依赖放在 `crm-api` / `crm-infra`，而不是父 POM 的 `<dependencies>` | 保证 `crm-worker` 不继承 web 依赖；整体依赖集合仍覆盖 issue §2.1 的要求 |
| 6 | `crm-worker` 只依赖 `spring-boot-starter`，没有 web 依赖 | 按 issue "直接不引 web 依赖"；本任务不启动 worker |
| 7 | Maven Wrapper 只有 `mvnw`、`mvnw.cmd`、`maven-wrapper.properties`，没有 `maven-wrapper.jar` | Wrapper 3.3.2 首次运行会自动下载 jar，issue 也明确说不需要 |
| 8 | Flyway 在共享库中新增了 `flyway_schema_history` | 启用 Flyway 的必然结果；16 张业务表未变，`shared/db` 未改 |
| 9 | `spring-boot:run -pl crm-api` 前需要执行一次 `./mvnw install` | Maven `-pl` 不把兄弟模块放入 reactor；issue §5.1 的 `package` 后直接 `run` 在干净本地仓库会失败。已在报告中给出可用命令 |
| 10 | 根 `pages.json` 是符号链接，Windows 检出可能表现不同 | 本仓库/CI 为 Linux；CLI 构建始终读 `src/pages.json`，不受影响 |

## 4. 未完成 / 限制

- 未实现任何业务逻辑或业务 UI：只有 `/api/v1/health` 和 10 个居中文字占位页；登录、列表、档案、发现、跟进等均留给后续任务。
- 未实现 JWT 校验/登录、Redis 读写、OSS、worker 定时任务；只提供了配置和空骨架。
- 未写后端自动化测试（本任务没有业务代码）；用 `package` + 实际启动 + `curl /health` 验证。`spring-boot-starter-test` 依赖已就位。
- 容器内没有可用浏览器，未做 H5 截图/真机截图；本任务明确"不实现 UI"，且没有旧 UI 基线可做 before/after 对比。
- `npm install` 的 4 个 install scripts 未获 npm 11 allowScripts 授权；esbuild 平台包已存在，`npm run build` 与 `vue-tsc` 均通过。若后续需要运行依赖 postinstall 的脚本，可执行 `npm install-scripts approve <pkg>`。
- 启动时 MyBatis-Plus 会对 `OrgTagRel` 打一条 "Not found @TableId" 警告；该表是复合主键，按 issue "先建实体不启用"，未强行给单列加 `@TableId`。
- 首次 Maven 依赖下载使用临时阿里云镜像（仅 `/tmp/maven-settings.xml`），未写入仓库；在只有 Maven Central 的环境下首次构建会慢一些，但可完成。
