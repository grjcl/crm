# 商旅酒店分销 CRM · 工程约定

## 这是什么

商旅酒店分销内部 CRM（一期）。上游对接酒店集团/TMC/批发商拿房量与协议价，下游卖 TMC 与商旅分销公司。一期把"人与关系"装进手机：合作商档案 + 发现 + 商机 + 跟进 + 下次跟进计划。

**规模**：10 人使用（销售 6–8 / 管理员 1–2）｜**接口 55 个**｜**数据表 16 张**

## 文档地图

| 目录 | 内容 | 优先级 |
|---|---|---|
| `docs/PRD_一期_v1.3.md` | **需求基线**。§6.1 合作商字段、§12 数据模型、§13.2 接口清单、§14 权限矩阵 | 最高 |
| `docs/CRM技术选型方案.md` | 技术栈与版本锁定（§7） | 高 |
| `docs/crm-phase1-prototype.html` | **UI 唯一基线**（V1.14，9 屏 14 深链） | 高 |
| `spec/` | 已完成的拆解：179 任务 / 267 条 AC / 23 决策 | 执行时主用 |

### spec 目录怎么用

- `01-数据模型.md` — 16 张表的完整 DDL 与字段说明
- `02-接口任务.md` — 55 个接口的任务定义
- `05-开发任务清单.csv` — **任务主表**（179 行，按 slice 分）。**以 CSV 为准**，MD 版统计表有旧数据
- `05-附录-验收标准全表.md` — 267 条 AC，每条标注校验方式
- `09-决策回执给分析师.md` — **23 个决策的最终结论**，有疑问先看它

## 硬约束

### 1. 契约优先

`shared/contracts/openapi.yaml` 与 `shared/db/V1__init.sql` 是唯一事实来源。改实现前先改契约。

### 2. 表名不能错

```
✅ follow_plan    ❌ plan
✅ sys_user       ❌ user
❌ 不建 role 表    → 角色是 sys_user.role 字段（SALES/ADMIN）
```

### 3. 不发明

- **不发明字段**：PRD 没写的不加
- **不发明 UI**：`docs/crm-phase1-prototype.html` 是唯一基线，原型没有的界面不要自己设计
- **不做范围外**：`spec/00-拆解总览与追溯矩阵.md` §4 有明确不做清单

### 4. 版本锁定

严格按 `docs/CRM技术选型方案.md` §7，不得擅自升级：

```
Java 21 / Spring Boot 3.3.x / MyBatis-Plus 3.5.x / Flyway 10.x
Vue 3.5 / TypeScript 5.x / uni-app 3.x / Pinia 2.x
PostgreSQL 16 / Redis 7
```

### 5. 接口约定

```
前缀  /api/v1
鉴权  Authorization: Bearer {accessToken}
响应  { "code": 0, "message": "ok", "data": ... }
分页  ?page=1&size=20 → { list, total, page, size }
时间  YYYY-MM-DD / YYYY-MM-DDTHH:mm:ss+08:00
幂等  POST 写操作带 Idempotency-Key
```

### 6. 施工顺序

按 `spec/05-开发任务清单.csv` 的 `deps` 字段走依赖。S0 是门禁：必须打通「登录 → 列表 → 详情 → 记跟进」全链路，才允许前后端并行。

## 环境说明

### 已就绪

| 工具 | 版本 | 路径 / 说明 |
|---|---|---|
| Node.js | 24.22.3 | `node` |
| npm | 11.19.1 | `npm` |
| Java | **21.0.13.1** (Temurin) | `/home/dsh/.local/opt/jdk-21` |
| psql | 16.16 | `psql` |
| git | 2.39.5 | `git` |
| gh | 2.105.0 | 走 `gh-agent` 包装器，已登录 |
| lark-cli | 1.0.100 | 走 `lark-bot` 包装器，bot 身份 |

### 关键：JAVA_HOME 必须显式设置

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
```

**每个新 shell 都要重新 export** —— 环境变量不跨命令保留。所有涉及 Maven 的命令前面都要带上它。

### Maven 用 Wrapper，不要装

**不需要安装 Maven**。`crm-server/` 下应该有 `mvnw`，第一次执行会自动下载 Maven 到 `~/.m2/wrapper/`，之后复用缓存。

```bash
cd crm-server
JAVA_HOME=/home/dsh/.local/opt/jdk-21 ./mvnw -q compile
```

如果仓库里还没有 `mvnw`，用这条命令生成（只需要 Java，不需要 Maven）：

```bash
# Spring Boot 脚手架自带；若手工建项目，从官方模板取 4 个文件：
#   mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties, .mvn/wrapper/maven-wrapper.jar
```

### 数据库

**不要在本机起临时 PostgreSQL**，用这台已经跑着的实例：

```
host: 192.168.1.53
port: 5432
user: root
password: 123456
database: crm        ← 已存在
```

连接串：

```
postgresql://root:123456@192.168.1.53:5432/crm
```

验证：

```bash
psql "postgresql://root:123456@192.168.1.53:5432/crm" -tAc "select version()"
```

**注意**：
- 这是共享库，**不要 DROP DATABASE**
- 建表前确认表不存在；如需重置用 `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`（先确认没有别人的数据）
- 测试用的临时表请加前缀 `t_`，用完删除

### 验证编译的完整命令

```bash
export JAVA_HOME=/home/dsh/.local/opt/jdk-21
cd crm-server && ./mvnw -q -DskipTests package
```

### 前端

```bash
cd crm-app && npm install && npm run build
```

npm 已配置国内镜像（`registry.npmmirror.com`）。

## 提交规范

```
feat|fix|refactor(scope): 描述
```

分支：`main`（生产）/ `develop`（联调）/ `feature/xxx`
