// [목적] 알림 이력·설정 API 타입 + TanStack Query 훅
// [이유] 알림 센터·설정 페이지에서 서버 상태 관리. page/sort 파라미터 추가(spec §1.4 페이지네이션)
// [사이드 임팩트] useUpdateNotificationSettings·useTestNotification onError → Sonner toast.error 발화.
//   알림 읽음 처리는 백엔드 is_read 미구현 — 로컬 Set으로 임시 처리
// [수정 시 고려사항] is_read 컬럼·PATCH API 추가 후 useMarkAsRead/useMarkAllAsRead 교체.
//   useTestNotification은 설정 검증용 — 실제 알림 발송 트리거

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
  sort?: string;
}

export function useNotifications(params?: NotificationListParams) {
  const query = new URLSearchParams();
  if (params?.size !== undefined)  query.set("size",  String(params.size));
  if (params?.page !== undefined)  query.set("page",  String(params.page));
  if (params?.sort !== undefined)  query.set("sort",  params.sort);
  const qs = query.toString() ? `?${query}` : "";

  return useQuery({
    queryKey: ["notifications", params],
    queryFn: () => apiClient<{ content: Notification[] }>(`/notifications${qs}`),
  });
}

export function useNotificationSettings() {
  return useQuery({
    queryKey: ["notification-settings"],
    queryFn: () => apiClient<NotificationSettings>("/notifications/settings"),
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
    onSuccess: () => qc.invalidateQueries({ queryKey: ["notification-settings"] }),
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
