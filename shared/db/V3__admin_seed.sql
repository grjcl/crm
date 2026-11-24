-- =====================================================================
-- 商旅酒店分销 CRM · V3 首个管理员账号
-- 依据：spec/01-数据模型.md §5（DB-SEED-002）
-- 本地测试明文密码：admin123
-- password_hash 为 admin123 的 BCrypt 哈希（cost 10），可重复执行。
-- 注意：生产环境请勿沿用该初始密码，首次登录后立即修改。
-- =====================================================================

BEGIN;

INSERT INTO sys_user (name, phone, dept, role, status, password_hash)
VALUES (
  '管理员',
  '13800000000',
  '商旅分销部',
  'ADMIN',
  'ACTIVE',
  '$2a$10$RAqFzCphadGpra50DcStsOFQIN/8QBUc2jim4mVoGzJo2w12.C4kO'  -- 明文：admin123
)
ON CONFLICT (phone) DO NOTHING;

COMMIT;
