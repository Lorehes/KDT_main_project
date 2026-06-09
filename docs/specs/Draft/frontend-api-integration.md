---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-09
---

# 프론트엔드 백엔드 API 실연동 Spec

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

`frontend-full-ui-implementation`(Done)에서 구현한 모든 TanStack Query 훅은 타입·구조만 완성된 상태이며 실제 백엔드 API와 연결되지 않았다. 이 Spec은 mock 데이터 의존을 제거하고 백엔드 REST API와 완전히 연동하는 작업을 다룬다.

- **현황**: `lib/api/client.ts`의 `apiClient<T>`는 `credentials: "include"` + `NEXT_PUBLIC_API_URL` 기반으로 작성되어 있으나, 환경변수가 없으면 localhost:8080으로만 요청. 토큰 갱신 인터셉터 미구현.
- **목표**: 모든 페이지가 실제 백엔드 응답으로 렌더됨. 401 시 자동 토큰 갱신.
- **BM 연관**: Free/Pro/Premium 티어 차등 응답(api_spec.md §2.4)이 공시 상세 TierGate에 반영됨.

---

## 요구사항

- [ ] **R1** `NEXT_PUBLIC_API_URL` 환경변수 설정 — `.env.local`(개발) / 배포 환경변수 주입
- [ ] **R2** 401 응답 시 `POST /auth/refresh` 재시도 인터셉터 — `apiClient`에 자동 토큰 갱신 추가
- [ ] **R3** 대시보드 실데이터 — `useDisclosures({scope:"portfolio"})` + `usePortfolios()` 실연동
- [ ] **R4** 공시 피드 실데이터 — `useDisclosures` 필터·페이지네이션 실연동
- [ ] **R5** 공시 상세 실데이터 — `useDisclosure` + `useDisclosureAnalysis` 실연동. 티어 미달 필드 `undefined` → TierGate 정상 렌더 확인
- [ ] **R6** 포트폴리오 실데이터 — `usePortfolios` + `useCreatePortfolio` + `useDeletePortfolio` 실연동
- [ ] **R7** 알림 실데이터 — `useNotifications` + `useNotificationSettings` + `useUpdateNotificationSettings` 실연동
- [ ] **R8** 분석 피드백 실연동 — `useFeedbackMutation` POST 성공 여부 검증
- [ ] **R9** authStore `fetchMe()` 초기화 — `(app)/layout.tsx` 또는 Providers에서 마운트 시 1회 호출
- [ ] **R10** 에러 상태 UI — 네트워크 오류·500 시 Toast/인라인 에러 표시 (현재 무응답)

---

## 영향 범위

- **영향 레이어**: frontend 전용. 백엔드 변경 없음.
- **DB 변경**: 없음
- **외부 계약**: 없음

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `frontend/.env.local` (신규) | `NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1` |
| `frontend/src/lib/api/client.ts` | 401 재시도 인터셉터 추가 |
| `frontend/src/app/(app)/layout.tsx` | `fetchMe()` 초기화 호출 |
| `frontend/src/app/(app)/dashboard/page.tsx` | 실데이터 로딩·에러 상태 보완 |
| `frontend/src/app/(app)/disclosures/page.tsx` | 페이지네이션 추가 |
| `frontend/src/app/(app)/disclosures/[id]/page.tsx` | 티어 미달 undefined 처리 검증 |

---

## 관련 패턴 / 과거 사례

- `lib/api/client.ts` — Bearer JWT + httpOnly cookie `dr_session` 구조 완성
- `api_spec.md §1.2` — 401 TOKEN_EXPIRED → `POST /auth/refresh` → 재발급
- `api_spec.md §1.4` — 페이지네이션 envelope `{ content, page }` 구조

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| 401 무한 루프 | refresh 요청 자체가 401이면 로그아웃 처리. 재시도 1회 제한 |
| 티어 미달 필드 undefined | `analysis?.similar_disclosures` 옵셔널 체이닝 — 이미 적용됨. 렌더 테스트 필요 |
| CORS | 백엔드 `application.yml`의 `allowed-origins`에 FE 도메인 등록 필요 |

---

## 권장 구현 방향

- `apiClient` 인터셉터 패턴: 401 수신 → `/auth/refresh` 1회 시도 → 성공 시 원 요청 재실행 → 실패 시 `authStore.logout()` 호출
- `(app)/layout.tsx`에서 `useEffect(() => fetchMe(), [])` 추가 — 페이지 리프레시 시 user 복원
- 에러 Toast: shadcn `Sonner` 또는 간단한 `useToast` 훅 추가

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
