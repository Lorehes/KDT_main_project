// [목적] 가입·로그인 폼 Zod 검증 스키마
// [이유] React Hook Form + Zod resolver로 클라이언트 인라인 검증. LLM 응답도 동일 스키마로 파싱(환각 방지)
// [사이드 임팩트] 정책 변경(비밀번호 길이 등) 시 이 파일만 수정하면 전체 폼에 반영
// [수정 시 고려사항] DISCLAIMER 동의는 자본시장법 제6조·제17조 관련 — agreed: true 강제(refined)

import { z } from "zod";

export const loginSchema = z.object({
  email: z.string().email("이메일 형식이 올바르지 않습니다"),
  password: z.string().min(8, "비밀번호는 8자 이상이어야 합니다"),
});

export const signupSchema = loginSchema.extend({
  nickname: z.string().max(20).optional(),
});

export const termsSchema = z.object({
  TERMS: z.literal(true, { error: "서비스 이용약관에 동의해주세요" }),
  PRIVACY: z.literal(true, { error: "개인정보 수집·이용에 동의해주세요" }),
  DISCLAIMER: z.literal(true, { error: "정보 제공 도구 동의가 필요합니다" }),
  AGE: z.literal(true, { error: "만 14세 이상이어야 합니다" }),
  MARKETING: z.boolean().default(false),
});

export const phoneSchema = z.object({
  phone: z
    .string()
    .regex(/^010\d{8}$/, "010으로 시작하는 11자리 번호를 입력해주세요"),
  code: z.string().length(6, "6자리 인증번호를 입력해주세요"),
});

export type LoginFormValues = z.infer<typeof loginSchema>;
export type SignupFormValues = z.infer<typeof signupSchema>;
export type TermsFormValues = z.infer<typeof termsSchema>;
export type PhoneFormValues = z.infer<typeof phoneSchema>;
