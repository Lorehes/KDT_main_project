// [목적] 알림 이력·설정 API 타입 + TanStack Query 훅 (읽음 처리·페이지네이션 포함)
// [이유] 알림 센터·설정 페이지에서 서버 상태 관리. page/size 파라미터로 BE PageResponse 페이지네이션 지원.
//   V18: is_read 컬럼 + PATCH API 추가 → 로컬 Set 임시 처리를 서버 영속화로 교체.
//   notification-pagination-fe Spec: NotificationPage 타입 추가, useNotifications 반환 타입을
//   apiClient<NotificationPage>로 전환 — total_pages(snake_case)로 hasNext 판별 지원.
// [사이드 임팩트] useNotifications 반환 타입이 { content } → NotificationPage로 확장됨.
//   호출 측(notifications/page.tsx)은 data?.page.total_pages로 hasNext 판별 가능.
//   BE sort는 createdAt DESC 고정(NotificationController 주석 참조) — sort 파라미터 전달 불필요.
//   useMarkAsRead/useMarkAllAsRead mutation 성공 시 ["notifications", "unread-count"] 쿼리 invalidate.
//   useUnreadCount: staleTime 30초 — TopBar 벨 뱃지 실데이터. WebSocket 도입 시 대체 가능.
//   useNotificationSettings: staleTime 5분 — 설정은 자주 변경되지 않음.
//   useUpdateNotificationSettings: onSuccess → 쿼리 무효화 + Sonner toast.success / onError → toast.error.
// [수정 시 고려사항] WebSocket 연결 시 useUnreadCount 폴링 → 서버 푸시 이벤트 구독으로 교체.
//   size 기본값(20)은 BE NotificationController @RequestParam defaultValue와 일치 — 변경 시 동기 필요.

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { apiClient, ApiException } from "./client";
import type { Sentiment } from "./disclosures";

export type NotifChannel = "KAKAO" | "TELEGRAM" | "EMAIL";
export type NotifFrequency = "INSTANT" | "DAILY_1" | "DAILY_2" | "WEEKLY";
export type NotifTypeFilter = "POSITIVE_ONLY" | "NEGATIVE_ONLY" | "ALL";

export interface Notification {
  id: number;
  disclosure_id: number;
  corp_name: string;
  report_nm: string;
  sentiment: Sentiment;
  channel: NotifChannel;
  status: "SENT" | "PENDING" | "FAILED";
  is_read: boolean;
  created_at: string;
}

export interface NotificationSettings {
  channel: NotifChannel;
  enabled: boolean;
  frequency: NotifFrequency;
  type_filter: NotifTypeFilter;
  off_hours_allowed: boolean;
}

export interface NotificationListParams {
  size?: number;
  page?: number;
}

/** BE PageResponse<NotificationDto> 응답 구조.
 *  page 메타는 snake_case — PageResponse.PageMeta @JsonProperty 직렬화와 1:1 대응.
 *  disclosures.ts DisclosurePage와 동일 패턴. */
export interface NotificationPage {
  content: Notification[];
  page: { number: number; size: number; total_elements: number; total_pages: number };
}

export function useNotifications(params?: NotificationListParams) {
  const query = new URLSearchParams();
  if (params?.size !== undefined) query.set("size", String(params.size));
  if (params?.page !== undefined) query.set("page", String(params.page));
  const qs = query.toString() ? `?${query}` : "";

  return useQuery({
    queryKey: ["notifications", params],
    queryFn: () => apiClient<NotificationPage>(`/notifications${qs}`),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}

export function useNotificationSettings() {
  return useQuery({
    queryKey: ["notification-settings"],
    queryFn: () => apiClient<NotificationSettings>("/notifications/settings"),
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: true,
  });
}

export function useUpdateNotificationSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Partial<NotificationSettings>) =>
      apiClient<NotificationSettings>("/notifications/settings", {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["notification-settings"] });
      toast.success("알림 설정이 저장됐습니다.");
    },
    onError: (err) =>
      toast.error(err instanceof ApiException ? err.body.message : "설정 저장에 실패했습니다."),
  });
}

export function useTestNotification() {
  return useMutation({
    mutationFn: () => apiClient("/notifications/test", { method: "POST" }),
    onError: (err) =>
      toast.error(err instanceof ApiException ? err.body.message : "테스트 알림 발송에 실패했습니다."),
  });
}

/** 단건 읽음 처리 — PATCH /notifications/{id}/read. 성공 시 notifications + unread-count 쿼리 무효화. */
export function useMarkAsRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      apiClient<void>(`/notifications/${id}/read`, { method: "PATCH" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["notifications"] });
      qc.invalidateQueries({ queryKey: ["unread-count"] });
    },
  });
}

/** 전체 읽음 처리 — PATCH /notifications/read-all. 성공 시 notifications + unread-count 쿼리 무효화. */
export function useMarkAllAsRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () =>
      apiClient<void>("/notifications/read-all", { method: "PATCH" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["notifications"] });
      qc.invalidateQueries({ queryKey: ["unread-count"] });
    },
  });
}

/** 미읽음 알림 수 — GET /notifications/unread-count. staleTime 30초 폴링. TopBar 벨 뱃지용. */
export function useUnreadCount() {
  return useQuery({
    queryKey: ["unread-count"],
    queryFn: () => apiClient<{ count: number }>("/notifications/unread-count"),
    staleTime: 30_000,
    select: (data) => data.count,
  });
}
