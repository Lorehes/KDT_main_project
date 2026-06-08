package com.dartcommons.notification.repositories;

import com.dartcommons.notification.entities.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/*
 * [목적] notifications 테이블(V6) CRUD + 상태 기반 조회 제공.
 *       중복 발송 방어는 DB uq_notification_dedup UNIQUE 제약 경유 — 애플리케이션은 DataIntegrityViolationException 포착.
 * [이유] Wave 1 Spec: 알림 발송 후 이력 기록, 재시도 배치(Wave 3+) 대상 조회(PENDING/RETRYING).
 * [사이드 임팩트] findByStatus는 idx_notifications_status partial 인덱스(PENDING·RETRYING만 커버) 활용.
 *               findByUserId는 idx_notifications_user 복합 인덱스(user_id, created_at DESC) 활용.
 * [수정 시 고려사항] 대량 재시도 배치는 Pageable + @Lock(PESSIMISTIC_WRITE) 추가 검토.
 *                  알림 히스토리 API 필요 시 findByUserIdAndCreatedAtAfter 등 날짜 범위 쿼리 추가.
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /** 사용자 알림 이력 조회 (idx_notifications_user 활용). */
    List<NotificationEntity> findByUserId(Long userId);

    /** 재시도 배치 대상 조회 (idx_notifications_status partial 인덱스 활용). */
    List<NotificationEntity> findByStatus(NotificationEntity.Status status);
}
