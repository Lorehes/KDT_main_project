// [목적] 가입 4단계 플로우 임시 상태 관리 — 이메일·비밀번호·동의 정보를 단계 간 전달
// [이유] 가입은 최종 단계(terms)에서 POST /auth/signup을 한 번만 호출. 각 step에서 수집한 데이터를 클라이언트에서 보존
// [사이드 임팩트] 민감 데이터(비밀번호)를 메모리에만 보유 — 새로고침 시 초기화. 이 경우 /signup으로 복귀
// [수정 시 고려사항] 비밀번호는 절대 sessionStorage/localStorage에 저장 금지. Zustand 메모리 한정.
//   가입 완료(completeSignup) 후 반드시 clear() 호출해 메모리 제거

import { create } from "zustand";

interface SignupState {
  email: string;
  password: string;
  nickname: string;
  consents: Record<string, boolean>;
  setEmail: (email: string) => void;
  setPassword: (password: string) => void;
  setNickname: (nickname: string) => void;
  setConsents: (consents: Record<string, boolean>) => void;
  clear: () => void;
}

const initialState = {
  email: "",
  password: "",
  nickname: "",
  consents: {},
};

export const useSignupStore = create<SignupState>((set) => ({
  ...initialState,
  setEmail: (email) => set({ email }),
  setPassword: (password) => set({ password }),
  setNickname: (nickname) => set({ nickname }),
  setConsents: (consents) => set({ consents }),
  clear: () => set(initialState),
}));
