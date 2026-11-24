-- =====================================================================
-- 商旅酒店分销 CRM · V2 基础字典种子
-- 依据：spec/01-数据模型.md §5（标签 3 个 / 来源 7 个）
-- 幂等：全部 INSERT ... ON CONFLICT DO NOTHING，可重复执行。
-- =====================================================================

BEGIN;

-- 合作标签：分销 / 供应 / 其他（sort 升序展示，值取 spec §5）
INSERT INTO org_tag (name, sort) VALUES
  ('分销', 10),
  ('供应', 20),
  ('其他', 30)
ON CONFLICT (name) DO NOTHING;

-- 发现来源：7 条（默认选「转介绍」由前端/服务端决定，此处只给顺序）
INSERT INTO lead_source (name, sort) VALUES
  ('转介绍', 10),
  ('展会',   20),
  ('同行',   30),
  ('官网',   40),
  ('陌拜',   50),
  ('公众号', 60),
  ('招标',   70)
ON CONFLICT (name) DO NOTHING;

COMMIT;
