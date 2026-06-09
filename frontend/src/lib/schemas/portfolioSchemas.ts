// [목적] 종목 등록 폼 Zod 검증 스키마
// [이유] 매수가·수량은 선택 입력. 음수·비현실적 값 검증으로 사용자 실수 방지
// [사이드 임팩트] avg_buy_price·quantity 필드는 절대 console.log 금지(금융 개인정보)
// [수정 시 고려사항] 최대 수량·가격 상한은 서버 정책 확인 후 조정

import { z } from "zod";

export const portfolioSchema = z.object({
  stock_code: z.string().min(6).max(6),
  avg_buy_price: z
    .number({ error: "숫자를 입력해주세요" })
    .positive("0보다 커야 합니다")
    .optional(),
  quantity: z
    .number({ error: "숫자를 입력해주세요" })
    .int("정수를 입력해주세요")
    .positive("0보다 커야 합니다")
    .optional(),
  memo: z.string().max(100).optional(),
  notify_enabled: z.boolean().default(true),
});

export type PortfolioFormValues = z.infer<typeof portfolioSchema>;
