-- [목적] users 테이블에 온보딩 완료 타임스탬프 추가 — OAuth 로그인 is_new_user 판단 기준 전환
-- [이유] hasRequiredConsents() 기반 is_new_user는 약관 동의 완료 후 이후 단계(phone/profile/complete)를
--       건너뛰어도 returning user로 처리 → 온보딩 미완료 사용자가 대시보드로 이동하는 버그.
--       onboarding_completed_at: NULL=온보딩 미완료(is_new_user=true), NOT NULL=완료(is_new_user=false).
-- [사이드 임팩트] 기존 사용자 중 consent_logs가 있는 경우 created_at으로 백필 — 기존 OAuth 사용자 경험 유지.
--               OAuth가 아닌 이메일 사용자도 /signup/complete 도달 시 설정되지만 로그인 분기에는 사용 안 함.
ALTER TABLE users ADD COLUMN onboarding_completed_at TIMESTAMPTZ;

COMMENT ON COLUMN users.onboarding_completed_at IS
    '온보딩 완료 타임스탬프. NULL = 미완료. /signup/complete 진입 시 POST /users/me/onboarding-complete 로 설정. OAuth 로그인 is_new_user 판단에만 사용.';

-- 백필: consent_logs가 있는 기존 사용자는 온보딩이 완료된 것으로 간주
UPDATE users u
SET onboarding_completed_at = u.created_at
WHERE EXISTS (
    SELECT 1 FROM consent_logs cl WHERE cl.user_id = u.id
);
