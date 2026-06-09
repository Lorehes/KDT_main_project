// [목적] 프로필 입력(온보딩 Step 4) + 마이페이지 수정 Zod 스키마
// [이유] 투자 경험·주 사용 시점은 선택 사항으로 기본값 유지 가능
// [사이드 임팩트] PATCH /users/me 요청 body와 타입 일치 필요
// [수정 시 고려사항] 새 개인화 옵션 추가 시 enum 및 백엔드 API 동시 업데이트

import { z } from "zod";

export const profileSchema = z.object({
  nickname: z.string().min(1).max(20).optional(),
  investment_experience: z.enum(["BEGINNER", "INTERMEDIATE", "ADVANCED"]).optional(),
  preferred_time: z.enum(["REALTIME", "LUNCH", "EVENING"]).optional(),
});

export type ProfileFormValues = z.infer<typeof profileSchema>;
