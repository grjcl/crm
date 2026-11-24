-- =====================================================================
-- 商旅酒店分销 CRM · V1 基础建库脚本
-- 依据：spec/01-数据模型.md（完整 DDL 已定稿，本文件只做按序组装）
-- 目标库：PostgreSQL 16
-- 说明：16 张表 + 14 个 updated_at 触发器 + 9 条契约索引与辅助索引
--       + 1 个视图 + pg_trgm 扩展与名称 GIN 索引。
--       触发器只建在带 updated_at 的表上：org_tag_rel 无 updated_at、
--       audit_log 只增不改，两者均不建触发器（spec §2.6 / §2.16）。
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 0. 函数 set_updated_at()：所有表的 updated_at 统一由触发器维护
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------
-- 1. sys_user 用户（DB-TBL-01）
-- ---------------------------------------------------------------------
CREATE TABLE sys_user (
  id             bigserial PRIMARY KEY,
  name           varchar(30)  NOT NULL,                       -- 姓名，列表与时间轴展示
  phone          varchar(11)  NOT NULL,                       -- 登录手机号，^1[3-9]\d{9}$
  employee_no    varchar(20),                                 -- 工号，如 1002
  dept           varchar(40),                                 -- 部门，如 商旅分销部
  role           varchar(10)  NOT NULL DEFAULT 'SALES'
                 CHECK (role IN ('SALES','ADMIN')),           -- 一期仅两个角色（PRD §1.3）
  status         varchar(10)  NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','DISABLED')),     -- 停用即刻下线（PRD §5.2）
  password_hash  varchar(72)  NOT NULL,                       -- BCrypt，60 位定长，留余量
  pwd_changed_at timestamptz,                                 -- 管理员重置密码时间，用于提示改密
  last_login_at  timestamptz,
  created_at     timestamptz  NOT NULL DEFAULT now(),
  updated_at     timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 2. org_tag 标签字典（DB-TBL-05）
-- ---------------------------------------------------------------------
CREATE TABLE org_tag (
  id         bigserial PRIMARY KEY,
  name       varchar(10)  NOT NULL,                           -- 标签名，≤10 字
  sort       int          NOT NULL DEFAULT 0,                 -- 展示顺序，升序
  enabled    boolean      NOT NULL DEFAULT true,              -- 停用后不在筛选与新档里出现
  created_at timestamptz  NOT NULL DEFAULT now(),
  updated_at timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 3. lead_source 来源字典（DB-TBL-09）
-- ---------------------------------------------------------------------
CREATE TABLE lead_source (
  id         bigserial PRIMARY KEY,
  name       varchar(10) NOT NULL,
  sort       int         NOT NULL DEFAULT 0,
  enabled    boolean     NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 4. channel 采集渠道（DB-TBL-14）
-- ---------------------------------------------------------------------
CREATE TABLE channel (
  id          bigserial PRIMARY KEY,
  name        varchar(20)  NOT NULL,                          -- ≤20 字
  type        varchar(20),                                    -- 行业展会/招标公告/内容平台/行业媒体
  url         varchar(200),                                   -- 网址或公众号/名录来源
  keyword     varchar(100),                                   -- 空格分隔
  frequency   varchar(20)  NOT NULL DEFAULT '手动'
              CHECK (frequency IN ('每日 08:00','每周一 08:00','每月 1 日','手动')),
  scope       varchar(40),                                    -- 全国 / 华东、华南
  enabled     boolean      NOT NULL DEFAULT true,
  last_run_at timestamptz,
  last_error  varchar(200),                                   -- 单渠道失败重试 2 次后的最后错误
  total_found int          NOT NULL DEFAULT 0,                -- 累计入池条数
  created_at  timestamptz  NOT NULL DEFAULT now(),
  updated_at  timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 5. org 合作商（DB-TBL-02）
-- ---------------------------------------------------------------------
CREATE TABLE org (
  id               bigserial PRIMARY KEY,
  name             varchar(100) NOT NULL,                     -- 企业全称，2–100 字（PRD §6.1）
  short_name       varchar(20),                               -- 简称，默认取名称前 4 字（服务端填）
  credit_code      varchar(18),                               -- 统一社会信用代码，正则见 PRD §6.1
  legal_person     varchar(30),                               -- 法定代表人
  reg_capital      varchar(30),                               -- 注册资本，自由文本「5000 万元」
  found_date       date,                                      -- 成立时间，精度到月：存当月 01 日
  industry         varchar(30),                               -- 行业，自由文本
  city             varchar(20),
  address          varchar(120),                              -- 注册地址
  website          varchar(80),
  brief            varchar(200),                              -- 企业简介
  type             varchar(10)  NOT NULL DEFAULT '商旅分销'
                   CHECK (type IN ('TMC','商旅分销','企业客户','其他')),   -- PRD §6.1 / 清单 3.2
  product_lines    jsonb        NOT NULL DEFAULT '[]'::jsonb,  -- 多选：协议价/包房/团体/机+酒（清单 3.3）
  level            varchar(10)  NOT NULL DEFAULT '重点'
                   CHECK (level IN ('战略','重点','普通')),    -- 分级（清单 3.1）
  status           varchar(10)  NOT NULL DEFAULT '潜在'
                   CHECK (status IN ('潜在','试单','合作中','暂停','流失')), -- 状态（清单 3.4）
  owner_id         bigint       NOT NULL REFERENCES sys_user(id), -- 归属销售（清单 2.8）
  collaborator_ids bigint[]     NOT NULL DEFAULT '{}',         -- 协作人，多选用户（C-01 已采纳，2026-11-24）
  created_by       bigint       NOT NULL REFERENCES sys_user(id),
  created_at       timestamptz  NOT NULL DEFAULT now(),
  updated_at       timestamptz  NOT NULL DEFAULT now(),
  deleted_at       timestamptz                                -- 软删；回收站 30 天（清单 2.10）
);

-- ---------------------------------------------------------------------
-- 6. org_invoice 开票信息（DB-TBL-03）
-- ---------------------------------------------------------------------
CREATE TABLE org_invoice (
  id         bigserial PRIMARY KEY,
  org_id     bigint       NOT NULL UNIQUE REFERENCES org(id) ON DELETE CASCADE,
  title      varchar(80),                                     -- 开票抬头，默认取企业全称
  tax_no     varchar(20),                                     -- 纳税人识别号，15–20 位
  bank       varchar(60),                                     -- 开户银行
  account    varchar(30),                                     -- 银行账号，仅数字，出参脱敏
  created_at timestamptz  NOT NULL DEFAULT now(),
  updated_at timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 7. org_supply 资源方扩展（DB-TBL-04）
-- ---------------------------------------------------------------------
CREATE TABLE org_supply (
  id             bigserial PRIMARY KEY,
  org_id         bigint      NOT NULL UNIQUE REFERENCES org(id) ON DELETE CASCADE,
  supplier_type  text[]      NOT NULL DEFAULT '{}',           -- 多选：酒店集团/单体酒店/TMC/批发商/同业
  cities         int,                                         -- 覆盖城市数
  hotels         int,                                         -- 合作酒店数
  room_type      varchar(60),                                 -- 主要房型
  peak_guarantee varchar(60),                                 -- 旺季保障
  coop_mode      text[]      NOT NULL DEFAULT '{}',           -- 多选：协议价/包房/代理/散拼
  coop_start     date,                                        -- 合作起始
  settle_type    varchar(10) CHECK (settle_type IN ('预付','月结')),
  credit_days    int         CHECK (credit_days IS NULL OR credit_days >= 0),
  settle_contact varchar(20),                                 -- 结算联系人（PRD §6.1 / 清单 4.4）
  access_status  varchar(10) CHECK (access_status IN ('考察中','合作中','暂停','淘汰')),
  reject_reason  varchar(100),                                -- 淘汰原因
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_supply_credit CHECK (settle_type IS DISTINCT FROM '月结' OR credit_days IS NOT NULL),
  CONSTRAINT ck_supply_reject CHECK (access_status IS DISTINCT FROM '淘汰' OR reject_reason IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- 8. org_tag_rel 合作商-标签（DB-TBL-06，联合主键，无独立 id）
-- ---------------------------------------------------------------------
CREATE TABLE org_tag_rel (
  org_id     bigint NOT NULL REFERENCES org(id) ON DELETE CASCADE,
  tag_id     bigint NOT NULL REFERENCES org_tag(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, tag_id)
);

-- ---------------------------------------------------------------------
-- 9. contact 联系人（DB-TBL-07）
-- ---------------------------------------------------------------------
CREATE TABLE contact (
  id         bigserial PRIMARY KEY,
  org_id     bigint       NOT NULL REFERENCES org(id) ON DELETE CASCADE,
  name       varchar(20)  NOT NULL,
  title      varchar(30),                                     -- 职务
  phone      varchar(20),                                     -- 手机或座机
  role_tag   varchar(10),                                     -- 决策人/对接人/财务（原型 k 字段）
  is_primary boolean      NOT NULL DEFAULT false,             -- 主联系人，拨号取它
  created_at timestamptz  NOT NULL DEFAULT now(),
  updated_at timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 10. attachment 附件（DB-TBL-15）
-- ---------------------------------------------------------------------
CREATE TABLE attachment (
  id          bigserial PRIMARY KEY,
  org_id      bigint       NOT NULL REFERENCES org(id),
  file_key    varchar(300) NOT NULL,                          -- OSS object key：partner/{orgId}/attach/{uuid}.{ext}
  file_name   varchar(200) NOT NULL,                          -- 原始文件名，展示用
  mime        varchar(100) NOT NULL,                          -- image/jpeg | image/png | application/pdf
  size        bigint       NOT NULL CHECK (size > 0),         -- 字节
  uploaded_by bigint       NOT NULL REFERENCES sys_user(id),
  created_at  timestamptz  NOT NULL DEFAULT now(),
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  deleted_at  timestamptz                                     -- 软删；列表不展示
);

-- ---------------------------------------------------------------------
-- 11. follow_plan 下次跟进计划（DB-TBL-13）
--     org_id 不写内联 FK：按 spec §2.13 在第 17 步补 fk_plan_org
-- ---------------------------------------------------------------------
CREATE TABLE follow_plan (
  id         bigserial PRIMARY KEY,
  org_id     bigint      NOT NULL UNIQUE,                     -- 一 org 一条（契约索引 1）
  plan_date  date        NOT NULL,
  note       varchar(60) NOT NULL,                            -- ≤60 字
  owner_id   bigint      NOT NULL REFERENCES sys_user(id),    -- 负责人，默认当前人
  version    int         NOT NULL DEFAULT 1,                  -- 乐观锁，对应错误码 4001
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 12. follow 跟进（DB-TBL-12）
--     deal_id 不写内联 FK：deal 在本表之后创建，见第 17 步
-- ---------------------------------------------------------------------
CREATE TABLE follow (
  id            bigserial PRIMARY KEY,
  org_id        bigint       NOT NULL REFERENCES org(id),
  contact_id    bigint       REFERENCES contact(id),          -- 可选关联联系人
  deal_id       bigint,                                       -- 可选关联商机（FK 见第 17 步）
  type          varchar(10)  NOT NULL DEFAULT '电话'
                CHECK (type IN ('电话','拜访','微信','邮件','系统')),
  content       varchar(500) NOT NULL,                        -- 1–500 字
  visible_scope varchar(20)  NOT NULL DEFAULT 'AUTO',         -- 自动：归属人+协作人+管理员（一期只存不用）
  created_by    bigint       NOT NULL REFERENCES sys_user(id),
  created_at    timestamptz  NOT NULL DEFAULT now(),          -- 业务时间，客户端不可改
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  deleted_at    timestamptz,
  delete_reason varchar(100),                                 -- 管理员删除时填，时间轴占位展示
  -- 二期预留，一期不入契约
  voice_url     varchar(300),
  voice_sec     int,
  photos        jsonb,
  lat           numeric(10,7),
  lng           numeric(10,7),
  addr          varchar(200)
);

-- ---------------------------------------------------------------------
-- 13. lead 我的发现（DB-TBL-08）
-- ---------------------------------------------------------------------
CREATE TABLE lead (
  id           bigserial PRIMARY KEY,
  org_name     varchar(100) NOT NULL,                         -- 企业名称，2–100 字
  contact_name varchar(20),
  phone        varchar(20),
  source_id    bigint       NOT NULL REFERENCES lead_source(id),
  city         varchar(20),
  status       varchar(10)  NOT NULL DEFAULT '待跟进'
               CHECK (status IN ('待跟进','已联系','已转合作商','已丢弃')),
  owner_id     bigint       NOT NULL REFERENCES sys_user(id),
  org_id       bigint       REFERENCES org(id),               -- 转合作商后回填
  remark       varchar(200),                                  -- 需求简述
  created_at   timestamptz  NOT NULL DEFAULT now(),
  updated_at   timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 14. lead_pool 待确认池（DB-TBL-10）
-- ---------------------------------------------------------------------
CREATE TABLE lead_pool (
  id            bigserial PRIMARY KEY,
  org_name      varchar(100) NOT NULL,
  channel_id    bigint       REFERENCES channel(id) ON DELETE SET NULL, -- 来源渠道；手动登记的来源可空
  source_detail varchar(200),                                 -- 如「2026 中国商旅管理峰会 · 参会名录」
  snippet       varchar(500),                                 -- 抽取值原文片段，供人判断
  city          varchar(20),
  raw_url       varchar(500),                                 -- 合规留痕：必须保留来源链接
  fingerprint   varchar(64)  NOT NULL,                        -- sha256(归一化名称 + '|' + 归一化电话)
  found_at      timestamptz  NOT NULL,                        -- 采集时间
  status        varchar(10)  NOT NULL DEFAULT '待确认'
                CHECK (status IN ('待确认','已认领','已丢弃')),
  claimed_by    bigint       REFERENCES sys_user(id),         -- 认领人
  claimed_at    timestamptz,
  lead_id       bigint       REFERENCES lead(id),             -- 认领后生成的发现记录
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 15. deal 商机（DB-TBL-11）
-- ---------------------------------------------------------------------
CREATE TABLE deal (
  id               bigserial PRIMARY KEY,
  org_id           bigint        NOT NULL REFERENCES org(id),
  title            varchar(50)   NOT NULL,                    -- ≤50 字
  stage            smallint      NOT NULL DEFAULT 0 CHECK (stage BETWEEN 0 AND 4),  -- 0接洽 1试单 2报价 3签约 4赢单
  stage_updated_at timestamptz   NOT NULL DEFAULT now(),      -- 每次 stage 变更刷新（停留天数口径）
  expect_nights    int           CHECK (expect_nights IS NULL OR expect_nights >= 0),
  expect_gmv       numeric(14,2) CHECK (expect_gmv IS NULL OR expect_gmv >= 0),
  expect_close     date,                                      -- 预计成交日期，≥ 今天（创建时校验）
  owner_id         bigint        NOT NULL REFERENCES sys_user(id),
  note             varchar(500),                              -- 说明
  result           varchar(10)   CHECK (result IN ('赢单','输单')),   -- 阶段 4 时必填
  lost_reason      varchar(10)   CHECK (lost_reason IN ('价格','资源覆盖','关系','需求变更','暂无预算')),
  closed_at        timestamptz,
  created_at       timestamptz   NOT NULL DEFAULT now(),
  updated_at       timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT ck_deal_result CHECK (stage <> 4 OR result IS NOT NULL),
  CONSTRAINT ck_deal_lost   CHECK (result IS DISTINCT FROM '输单' OR lost_reason IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- 16. audit_log 审计（DB-TBL-16，只增不改：无 updated_at、无 FK、无触发器）
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
  id         bigserial PRIMARY KEY,
  actor_id   bigint,                                          -- 不建 FK：用户停用/删除仍要能看
  action     varchar(40)  NOT NULL,                           -- CREAT/UPDATE/DELETE/LOGIN/RESET_PWD/TRANSFER/CLAIM...
  entity     varchar(30)  NOT NULL,                           -- org | follow | follow_plan | deal | tag | channel | sys_user ...
  entity_id  bigint,
  diff       jsonb,                                           -- {before:{...}, after:{...}} 或 {fields:[...]}
  ip         varchar(45),                                     -- IPv4/IPv6
  ua         varchar(200),
  created_at timestamptz  NOT NULL DEFAULT now()              -- 只增不改，无 updated_at
);

-- ---------------------------------------------------------------------
-- 17. 补充外键
--     fk_plan_org：follow_plan.org_id 的 FK（spec §2.13 / C-06）
--     fk_follow_deal：follow.deal_id → deal，deal 已在第 15 步建好（spec §1 方案 A）
-- ---------------------------------------------------------------------
ALTER TABLE follow_plan ADD CONSTRAINT fk_plan_org FOREIGN KEY (org_id) REFERENCES org(id);
ALTER TABLE follow ADD CONSTRAINT fk_follow_deal FOREIGN KEY (deal_id) REFERENCES deal(id);

-- ---------------------------------------------------------------------
-- 18. 触发器：带 updated_at 的 14 张表统一调用 set_updated_at()
--     （org_tag_rel 无 updated_at；audit_log 只增不改 → 均不建）
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_sys_user_updated_at BEFORE UPDATE ON sys_user
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_org_tag_updated_at BEFORE UPDATE ON org_tag
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_lead_source_updated_at BEFORE UPDATE ON lead_source
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_channel_updated_at BEFORE UPDATE ON channel
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_org_updated_at BEFORE UPDATE ON org
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_org_invoice_updated_at BEFORE UPDATE ON org_invoice
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_org_supply_updated_at BEFORE UPDATE ON org_supply
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_contact_updated_at BEFORE UPDATE ON contact
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_attachment_updated_at BEFORE UPDATE ON attachment
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_follow_plan_updated_at BEFORE UPDATE ON follow_plan
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_follow_updated_at BEFORE UPDATE ON follow
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_lead_updated_at BEFORE UPDATE ON lead
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_lead_pool_updated_at BEFORE UPDATE ON lead_pool
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_deal_updated_at BEFORE UPDATE ON deal
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- 19. 索引：9 条契约索引（标注「契约索引 n」）+ 辅助索引
-- ---------------------------------------------------------------------
-- sys_user
CREATE UNIQUE INDEX uk_user_phone ON sys_user(phone);
CREATE UNIQUE INDEX uk_user_employee_no ON sys_user(employee_no) WHERE employee_no IS NOT NULL;
-- org
CREATE INDEX idx_org_owner      ON org(owner_id)       WHERE deleted_at IS NULL;  -- 契约索引 3
CREATE INDEX idx_org_type_city  ON org(type, city)     WHERE deleted_at IS NULL;  -- 契约索引 4
CREATE INDEX idx_org_name       ON org(name)           WHERE deleted_at IS NULL;  -- 搜索/排序辅助
CREATE UNIQUE INDEX uk_org_credit_code ON org(credit_code)
  WHERE credit_code IS NOT NULL AND deleted_at IS NULL;   -- 信用代码唯一（排除空与已删）
-- org_tag
CREATE UNIQUE INDEX uk_org_tag_name ON org_tag(name);
-- org_tag_rel
CREATE INDEX idx_tag_rel_tag ON org_tag_rel(tag_id);
-- contact
CREATE UNIQUE INDEX uk_contact_primary ON contact(org_id) WHERE is_primary;
CREATE INDEX idx_contact_org ON contact(org_id);
-- lead
CREATE INDEX idx_lead_owner  ON lead(owner_id, status);
CREATE INDEX idx_lead_org    ON lead(org_id);
-- lead_source
CREATE UNIQUE INDEX uk_lead_source_name ON lead_source(name);
-- lead_pool
CREATE UNIQUE INDEX uk_pool_fp ON lead_pool(fingerprint);    -- 契约索引 2
CREATE INDEX idx_pool_status_time ON lead_pool(status, found_at DESC);
-- deal
CREATE INDEX idx_deal_org_stage  ON deal(org_id, stage);     -- 契约索引 6
CREATE INDEX idx_deal_stage_time ON deal(stage, stage_updated_at); -- 契约索引 7
CREATE INDEX idx_deal_owner      ON deal(owner_id, stage);   -- 「我的商机」分组
-- follow_plan
CREATE UNIQUE INDEX uk_plan_org ON follow_plan(org_id);      -- 契约索引 1
CREATE INDEX idx_plan_date ON follow_plan(plan_date);        -- 契约索引 8
-- follow
CREATE INDEX idx_follow_org_time ON follow(org_id, created_at DESC);   -- 契约索引 5
CREATE INDEX idx_follow_created_by ON follow(created_by, created_at DESC); -- 24h 编辑判定
-- attachment
CREATE INDEX idx_attach_org ON attachment(org_id) WHERE deleted_at IS NULL;  -- 契约索引 9
-- channel
CREATE UNIQUE INDEX uk_channel_name ON channel(name);
-- audit_log
CREATE INDEX idx_audit_entity ON audit_log(entity, entity_id, created_at DESC);
CREATE INDEX idx_audit_actor  ON audit_log(actor_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 20. 视图 v_partner_list（DB-VIEW-001）
--     列表查询：ORDER BY (plan_date IS NULL), plan_date, name
--     tags 不在视图内聚合：分页后按 id 批量查 org_tag_rel（PRD §12.3 注释）
-- ---------------------------------------------------------------------
CREATE VIEW v_partner_list AS
SELECT o.id, o.name, o.short_name, o.level, o.status, o.city, o.owner_id,
       p.plan_date, p.note AS plan_note
FROM org o
LEFT JOIN follow_plan p ON p.org_id = o.id
WHERE o.deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- 21. 扩展与模糊匹配索引（DB-EXT-001）
-- ---------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_org_name_trgm ON org USING gin (name gin_trgm_ops);

COMMIT;
