# 商旅酒店分销 CRM · 技术选型方案 V2.1（uni-app + Spring Boot）

> 决策：**客户端 uni-app（Vue3 + TS）｜ 后端 Spring Boot（Java 21）｜ PostgreSQL**
> 适用版本：PRD v1.3 / 功能清单 V1.13（一期 7 个功能点 / **50 个二级功能点，45 必做** / 粗估 **63.5 人天** / **54 个接口** / **16 张表**）
> 编写日期：2026-11-21（V2.0）｜2026-11-22（V2.1 修订）　｜　状态：路线已定，**契约已冻结**（tag `v0.2-contract`，见 §0.1）
>
> **V2.1 修订**：① 需求基线由「V1.10 / 54 点 / 67 人天」更正为「V1.13 / 50 点 / 63.5 人天」；② 表名统一为 PRD §12 口径（`follow_plan` / `sys_user`，删除不存在的 `role` 表）；③ 生物识别由「一期做」改为「一期不做」；④ 排期增加 Phase 0 前置与三个切片调整；⑤ 附件规格按类型拆分。

---

## 0. 一页总览

| 层 | 选型 | 版本基线 |
|---|---|---|
| 客户端框架 | uni-app（Vue3 + TypeScript + Vite） | uni-app 3.x / Vue 3.5 / TS 5.x |
| 客户端 UI | wot-design-uni（主）+ uni-ui（兜底） | 1.x |
| 状态 / 请求 | Pinia + 自封装 uni.request | Pinia 2.x |
| 后端框架 | Spring Boot 3（Web MVC） | 3.3.x 以上 |
| 运行时 | OpenJDK | 21 LTS |
| 数据访问 | MyBatis-Plus + Flyway | 3.5.x / 10.x |
| 数据库 | PostgreSQL | 16 |
| 缓存 / 队列 | Redis | 7.x |
| 文件 | 阿里云 OSS（客户端直传） | — |
| 部署 | Docker Compose + 单台 **4C8G 起** | — |
| 推送 | uni-push 2.0（含厂商通道，一期即接入） | — |

**人力：1 名全栈（项目负责人）+ 多个 AI agent 协作**，日历周期 **10–11 周**（含 5 个工作日开工前置）。
> Agent 能压缩编码时间，但压不动验收、真机调试与资质等待——排期按「人 + agent 混编」重估。

### 0.1 开工前置（V2.1 新增）

技术路线已定，契约已于 2026-11-22 冻结（tag `v0.2-contract`）：54 个接口的 `openapi.yaml` 与 16 张表的 `V1__init.sql` 已落盘，PG16 实测与 Prism mock 均通过。剩余前置如下。

| 前置项 | 产出 | 工期 | 状态 |
|---|---|---|---|
| 契约冻结 | `shared/contracts/openapi.yaml` + `shared/db/V1__init.sql` + `shared/CONVENTIONS.md` | 1.5 天 | ✅ 已完成 |
| 工程骨架 | 前后端可编译空壳 + `AGENTS.md` + CI 四关 | 1 天 | ⬜ D3 |
| 原型补齐 | 按 PRD §19.1 补 3 屏 + 1 下拉 + 1 角标 → V1.14 | 1 天 | ⬜ D4 |
| 资质启动 | 5 厂商推送通道 / ICP 备案 / 软著 / OSS / 开发者账号 | 并行，不占编码 | ⬜ D5 |

---

## 1. 客户端技术栈（uni-app）

| 用途 | 选型 | 版本 | 选它的理由 |
|---|---|---|---|
| 框架 | **uni-app（Vue3 组合式 API）** | 3.x | 一套代码出 iOS/Android，团队零学习成本；将来可编译小程序 |
| 语言 | **TypeScript** | 5.x | 接口字段多（合作商 20+ 字段），类型能挡掉一半低级 bug |
| 构建 | **Vite** + `@dcloudio/vite-plugin-uni` | 5.x | 秒级热更新；用 CLI 工程，**不依赖 HBuilderX 写代码** |
| 状态管理 | **Pinia** | 2.x | Vue3 官方推荐，比 Vuex 轻；按业务域拆 store（partner/lead/follow） |
| 路由 | **uni-app pages.json**（内置） | — | App 端不用 vue-router，避免双路由冲突 |
| UI 组件库 | **wot-design-uni**（主）<br>uni-ui（补充） | 1.x | Vue3 + TS 原生支持、组件质量高、可按需引入；能覆盖列表/表单/弹层/日期选择 |
| 图标 | 自绘 SVG + iconfont | — | 原型里的图标直接复用 |
| 网络请求 | **luch-request**（或自封装 uni.request） | 3.x | 拦截器、重试、统一错误码；比裸 request 少写 200 行 |
| 接口类型 | **openapi-typescript** 由后端 OpenAPI 生成 | 7.x | 后端改字段前端立刻编译报错，联调成本减半 |
| 本地存储 | `uni.setStorageSync`（轻量）<br>`plus.sqlite`（离线表，可选） | — | 一期只缓存「最近访问 + 待上传队列」，不做全量离线 |
| 图片 | `uni.chooseMedia` + 自写 canvas 压缩 | — | 合同照片压到 300KB 内再传，省流量也省存储 |
| 推送 | **uni-push 2.0** | — | **一期必接**（计划到期提醒）；支持华为/小米/OPPO/vivo/荣耀通道 + APNs；备选个推 |
| 生物识别 | 插件市场指纹/面容插件（或 UTS 自研插件） | — | **一期不做**（PRD 已剔除需求 1.5），二期评估；若后续启用，工作量 1–2 天 |
| 图表（二期） | **uCharts** | 2.x | uni-app 生态标准，canvas 渲染，跨端一致 |
| 日期/金额 | dayjs + 自写 formatter | — | 原型里的「5 天后 / 逾期 2 天」直接复用 |
| 打包 | HBuilderX 云打包（或本地离线打包） | — | 云打包省事；敏感项目可用本地打包（Android Studio + Xcode） |
| 升级 | 应用市场更新为主，`uni-upgrade-center` 做提示 | — | **不做 wgt 热更新**，避免 App Store 审核风险 |

### 客户端三条硬规矩

1. **不写 nvue**：nvue 性能好但生态弱、样式限制多，我们的列表量级用不到，写 nvue 反而拖慢进度。
2. **不用 uniCloud**：后端已定 Java，混用两套后端会让数据割裂。
3. **长列表必须分页**：20 条一页 + 上拉加载，禁止一次拉全量（WebView 渲染的短板在这里）。

---

## 2. 后端技术栈（Spring Boot）

| 用途 | 选型 | 版本 | 选它的理由 |
|---|---|---|---|
| 语言/运行时 | **OpenJDK 21 LTS** | 21 | LTS、虚拟线程可用；团队若已用 17 也能跑（Spring Boot 3 要求 17+） |
| 框架 | **Spring Boot 3（Web MVC）** | 3.3.x+ | 不用 WebFlux：10 人并发，MVC 更简单、排错更快 |
| 构建 | **Maven** | 3.9 | 比 Gradle 通用，外包交接成本低 |
| 安全 | **Spring Security 6 + JWT（jjwt）+ BCrypt** | 6.x / 0.12 | 无状态鉴权；密码 BCrypt 存储，错误 5 次锁定 15 分钟 |
| ORM | **MyBatis-Plus** | 3.5.x | 国内主流、CRUD 快、复杂 SQL 直接写 XML；比 JPA 更可控 |
| 迁移 | **Flyway** | 10.x | 数据库变更进 Git，环境可重建 |
| 校验 | Jakarta Validation | — | 接口入参校验，别在 Service 里写 if |
| 文档 | **SpringDoc OpenAPI 3 + Knife4j** | 2.x / 4.x | 生成 OpenAPI → 前端自动生成 TS 类型 |
| 连接池 | HikariCP（Spring 内置） | — | 默认即可，10 并发 10 个连接足够 |
| 缓存 | **Redis 7**（Spring Data Redis） | 7.x | 存 token 黑名单、字典缓存、短信验证码、限流计数 |
| 定时任务 | **Spring `@Scheduled` + ShedLock** | — | 单实例够用；ShedLock 防多实例重复执行。**不上 XXL-Job**（多余） |
| 文件上传 | 阿里云 OSS SDK（STS 直传） | 3.x | 后端只签发临时凭证，文件不过服务器，省带宽 |
| 日志 | Logback + JSON 格式 | — | 结构化日志便于检索 |
| 审计 | Spring AOP 切面 → `audit_log` 表 | — | 对应需求 27，记录谁改了哪条记录的哪些字段 |
| 测试 | JUnit 5 + MockMvc | — | 核心接口（鉴权、查重、计划排序）写单测 |
| 采集 worker | 独立模块：**Playwright for Java**（动态站）+ **jsoup**（静态站） | — | 与主服务分离，抓取慢/挂了不影响 APP |

### 后端四条硬规矩

1. **不做微服务**：单体 + 按业务域分模块（`partner/lead/follow/deal/channel/auth`），10 人系统拆微服务是灾难。
2. **不引消息队列**（RabbitMQ/Kafka）：定时任务 + 数据库状态机足够，Queue 只在采集 worker 内部用内存队列。
3. **接口一律 REST + 统一响应体** `{ code, message, data }`，错误码集中管理。
4. **敏感字段在接口层裁**：底价、返佣按角色决定是否返回，不靠客户端隐藏。

---

## 3. 数据与存储

| 项 | 选型 | 说明 |
|---|---|---|
| 主库 | **PostgreSQL 16** | 二期对账/间夜聚合要写窗口函数；`JSONB` 存自定义字段 |
| 字符集/时区 | UTF8 / Asia/Shanghai | 所有时间字段 `timestamptz` |
| 文件 | **OSS 标准存储** | 目录：`partner/{id}/attach/`、`follow/{id}/photo/` |
| 备份 | 每日 `pg_dump` → OSS，保留 30 天 | 每月做一次恢复演练 |
| 数据量估算 | 一期 < 1GB | 10 人、11 家合作商、每年几千条跟进，一台机器几年不用换 |

**核心表（一期，与 PRD §12.1 一致，共 16 张）**：
`sys_user`（用户，`role` 为字段而非独立表）、`org`（合作商）、`org_invoice`（开票信息）、`org_tag`（标签字典）、`org_tag_rel`（合作商-标签）、`org_supply`（资源方扩展）、`contact`（联系人，一期只读）、`lead`（我的发现）、`lead_source`（来源字典）、`lead_pool`（待确认池）、`deal`（商机）、`follow`（跟进记录）、`follow_plan`（下次跟进计划，**一 org 一条，唯一索引**）、`channel`（采集渠道）、`attachment`（附件）、`audit_log`（审计）。

> **表名以 PRD §12 为准**：`follow_plan`（不是 `plan`）、`sys_user`（不是 `user`）；一期只有 SALES / ADMIN 两个角色，**不建 `role` 表**，用 `sys_user.role` 字段承载。

---

## 4. 基础设施与部署

| 项 | 选型 | 说明 |
|---|---|---|
| 云 | 阿里云 / 腾讯云 轻量应用服务器 | **建议 4C8G 起**：单机要跑 5 个容器（nginx / api / worker / postgres / redis），2C4G 会被 JVM + PG 吃满导致 OOM；一年差价一两千，不值得省 |
| 容器 | **Docker + Docker Compose** | 四个容器：nginx / api / worker / postgres（Redis 可同机） |
| 反向代理 | **Nginx** | TLS 终止、gzip、静态资源、限流 |
| HTTPS | 云厂商免费证书 / Let's Encrypt | 90 天自动续期脚本 |
| CI/CD | **GitLab CI**（或 GitHub Actions） | 构建镜像 → 推镜像仓库 → 服务器 `compose pull && up -d` |
| 监控 | Spring Boot Actuator + 云监控告警 | 10 人系统不上 Prometheus/Grafana，够用就行 |
| 崩溃 | **腾讯 Bugly**（App）+ Sentry（可选） | 一期就接，别等线上炸了才加 |
| 日志 | 云日志服务（或本地 logrotate） | 保留 30 天 |

---

## 5. 第三方服务

| 能力 | 选型 | 何时用 | 费用量级 |
|---|---|---|---|
| 短信验证码 | 阿里云短信 | **一期不启用**（只保留密码登录），仅作备用 | 0.04 元/条 |
| 推送 | uni-push 2.0 | **一期启用**（需 5 家厂商通道 + APNs 证书） | 免费 + 厂商通道免费 |
| 名片 OCR | 腾讯云名片识别 | 三期 | 几百元/年 |
| 语音转写 | 讯飞 / 阿里云 | 三期 | 按量 |
| 地图 | 高德 SDK | 二期（拜访签到） | 免费额度够 |
| 崩溃 | Bugly | 一期 | 免费 |

---

## 6. 工程规范

### 6.1 目录结构

```
crm-app/                      # uni-app
├── src/
│   ├── pages/               # 登录 / 合作商 / 发现 / 我的
│   ├── components/          # biz-card、plan-card、timeline…
│   ├── store/               # Pinia: partner / lead / user
│   ├── api/                 # 按域拆：partner.ts / lead.ts
│   ├── types/               # 由 OpenAPI 自动生成
│   ├── utils/               # request、date、format
│   └── static/
└── package.json

crm-server/                   # Spring Boot 多模块（同一仓库）
├── crm-api/                 # controller + service
├── crm-domain/              # 实体、DTO、枚举
├── crm-infra/               # mapper、redis、oss
├── crm-worker/              # 采集与定时任务
└── pom.xml
```

### 6.2 接口约定

- 路径：`/api/v1/{domain}/{action}`，动词用 HTTP Method 表达
- 响应：`{ "code": 0, "message": "ok", "data": {...} }`，业务错误 `code != 0`
- 分页：`?page=1&size=20` → `{ list, total, page, size }`
- 鉴权：`Authorization: Bearer {accessToken}`，access 2h / refresh 30d
- 幂等：写操作带 `Idempotency-Key`（跟进、计划提交防重复）

### 6.3 分支与环境

- 分支：`main`（生产）/ `develop`（联调）/ `feature/xxx`
- 环境：`local` / `test`（同一台服务器 8081 端口）/ `prod`
- 提交规范：`feat|fix|refactor(scope): 描述`

---

## 7. 版本锁定清单（开工第一件事）

```jsonc
// package.json（客户端）
"vue": "3.5.x", "typescript": "5.x",
"@dcloudio/uni-app": "3.x", "pinia": "2.x",
"wot-design-uni": "1.x", "luch-request": "3.x",
"ucharts": "2.x", "dayjs": "1.x"
```

```xml
<!-- pom.xml（后端） -->
<java.version>21</java.version>
<spring-boot.version>3.3.x</spring-boot.version>
<mybatis-plus.version>3.5.x</mybatis-plus.version>
<flyway.version>10.x</flyway.version>
<jjwt.version>0.12.x</jjwt.version>
<springdoc.version>2.x</springdoc.version>
```

> 版本以开工当天最新稳定版为准，**一旦锁定写进 pom / package.json，不允许个人本地升级**。

---

## 8. 明确不采用的选项

| 选项 | 为什么不 |
|---|---|
| nvue / 原生渲染 | 生态弱、样式受限，我们的数据量用不上 |
| uniCloud 云开发 | 后端已定 Java，两套后端会数据割裂 |
| wgt 热更新 | App Store 对热更新有限制，审核风险大于收益 |
| XXL-Job / 微服务 / MQ | 10 人系统，`@Scheduled` + 单体足够，多一个组件多一个故障点 |
| Spring Data JPA | 二期对账需要手写复杂 SQL，MyBatis-Plus 更直接 |
| WebFlux | 并发只有 10，响应式只会增加调试难度 |
| Element Plus 等 H5 组件库 | 打包成 App 后大量组件不可用 |

---

## 9. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 安卓推送到达率 | 提醒收不到 = 功能失效 | 一期即申请 5 家厂商通道，验收标准写「到达率 ≥95%」 |
| WebView 长列表卡顿 | 首页滚动不顺 | 分页 20 条 + 图片懒加载 + 避免大图直接渲染 |
| 上架审核（软著/隐私） | 延期 1–2 周 | 第 1 周就申请软著；先 TestFlight/蒲公英内测 |
| 采集合规 | 抓站可能违规 | 只采公开企业信息、留来源链接、人工确认入库 |
| 单人依赖 | 人员变动项目停摆 | Git 托管 + 部署文档 + 至少 2 人能上线 |
| 厂商绑定（OSS/云） | 迁移成本 | 只用标准 PG + S3 兼容对象存储，不用私有 API |

---

## 10. 排期（单人 + agent 版）

> 原「2 人 × 8 周」的并行排期见文末备查。单人 + agent 改为**垂直切片**推进：
> 每个切片端到端（后端 → 前端 → 联调 → 真机），一次只开 2 个 agent（一前一后）。

| 阶段 | 内容 | 主要接口 | 工期 |
|---|---|---|---|
| **Phase 0** | 契约冻结（54 接口 / 16 表）、工程骨架、原型补齐、资质启动 | — | **5 天** |
| S0 | 脚手架、契约代码生成、登录打通（**门禁：登录→列表→详情→记跟进全链路通，才允许开并行**） | auth 4 个 | 3 天 |
| S1 | 字典、标签管理、来源字典（原「我的」壳移到 S7） | tags 4 + dicts 1 + lead-sources 3 + version 1 | **2 天** |
| S2 | 合作商 CRUD + 列表（标签筛选 + 计划排序） | partners 6 + plan 2 | 5 天 |
| S3 | 档案 360（含资源方区块、附件、联系人只读） | detail 1 + contacts 1 + sts 1 + attachment 2 | 4 天 |
| S4 | 跟进 + 计划（含排序联动、待跟进列表） | follows 4 + plan 2 | 4 天 |
| S5 | 发现（池 / 我的发现 / 商机） | leads 3 + pool 4 + deals 6 | 5 天 |
| **S6a** | 渠道配置 + 手动触发采集 | channels 6 | **2 天** |
| **S6b** | 自动采集 worker（Playwright + jsoup，**可延到内测后**） | — | **2 天** |
| S7 | 收尾：账号管理、交接、审计、埋点、Bugly、**「我的」壳**、打包 | admin 4 | 3 天 |

**合计 30 个工作日**（若 S6b 延后则 28 个），加 Phase 0 共 35 个；含联调、真机与资质等待，整体 **10–11 周日历时间**。

**V2.1 的三处排期调整**：

1. **S1 由 3 天压到 2 天**：「我的」页只是静态壳（头像 + 设置列表 + 占位页），挪到 S7 收尾，不影响任何验收节点。
2. **S6 拆为 S6a / S6b**：S6a（渠道配置页 + 手动触发单渠道）必做；S6b（自动采集 worker）依赖业务方指定 3–5 个真实渠道并签署合规确认，**未定则延到内测后**，不进关键路径。
3. **S0 增加硬门禁**：必须打通「登录 → 列表 → 详情 → 记跟进」全链路才允许前后端分头并行，避免接口签名一错两边白干。

> 接口归属已按 54 个重算（原方案切片相加为 46，与清单 44 对不上）：S0 4 / S1 9 / S2 8 / S3 4 / S4 6 / S5 13 / S6a 6 / S7 4 = **54**。

---

<details>
<summary>附：原 2 人并行排期（备查，已不适用）</summary>

### 原排期（2 人 × 8 周）

| 周 | 前端（Vue/uni-app） | 后端（Java） |
|---|---|---|
| W1 | 设计系统落地、路由与 Pinia 骨架、request 封装 | 库表设计、Flyway、鉴权、账号开通 |
| W2 | 登录、我的、标签管理 | 合作商/标签/联系人 CRUD + 查重 |
| W3 | 合作商列表（标签筛选 + 计划排序）、档案 360 | 列表聚合、搜索、360 聚合接口 |
| W4 | 新建/编辑、附件拍照上传（OSS 直传） | STS 签发、附件元数据、压缩回调 |
| W5 | 跟进时间轴、记跟进（文字/语音/照片） | 跟进与计划接口、自动生成计划 |
| W6 | 发现三视图、商机阶段推进 | 线索池、认领、商机状态机 |
| W7 | 渠道配置（增删改 + 开关 + 立即采集） | 采集 worker、去重、定时任务 |
| W8 | 联调、埋点、Bugly 接入、**推送接入与通道联调**、双端打包上架 | 10 并发压测、备份脚本、上线 |

**验收口径**：W2 能登录建档 → W5 销售可用 → W8 上架。

</details>
