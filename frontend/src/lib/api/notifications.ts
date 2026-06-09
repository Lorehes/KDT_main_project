// [목적] 알림 이력·설정 API 타입 + TanStack Query 훅
// [이유] 알림 센터·알림 설정 페이지에서 서버 상태 관리
// [사이드 임팩트] 알림 읽음 처리는 백엔드 미구현 — 현재 로컬 상태(uiStore)로 임시 처리
// [수정 시 고려사항] 백엔드에 is_read 컬럼 추가 후 markAsRead mutation을 PATCH API로 교체

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
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

export function useNotifications(params?: { size?: number }) {
  const query = params?.size ? `?size=${params.size}` : "";
  return useQuery({
    queryKey: ["notifications", params],
    queryFn: () => apiClient<{ content: Notification[] }>(`/notifications${query}`),
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
  });
}
