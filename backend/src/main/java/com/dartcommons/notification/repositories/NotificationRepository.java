package com.dartcommons.notification.repositories;

import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.entities.NotificationEntity.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/*
 * [목적] notifications 테이블(V6+V15) CRUD + 상태 기반 조회 + RetryJob 전용 조건부 RETRYING 전이 제공.
 *       중복 발송 방어는 DB uq_notification_dedup UNIQUE 제약 경유 — 애플리케이션은 DataIntegrityViolationException 포착.
 * [이유] Wave 1 RetryJob Spec: PENDING/RETRYING 고착 레코드 재발송을 위한 findRetryTargets()와
 *       조건부 UPDATE(markAsRetrying)로 이중 발송 원자성 방어(Tech Review High 리스크 처리).
 * [사이드 임팩트] findRetryTargets는 idx_notifications_status partial 인덱스(PENDING·RETRYING만 커버) 활용.
 *               markAsRetrying의 UPDATE는 sent_at IS NULL + status IN 조건으로 다른 인스턴스와 경쟁 방지.
 *               findByUserId는 idx_notifications_user 복합 인덱스(user_id, created_at DESC) 활용.
 * [수정 시 고려사항] 대량 재시도 배치는 Pageable + @Lock(PESSIMISTIC_WRITE) 추가 검토.
 *                  다중 인스턴스 배포 시 ShedLock + markAsRetrying 조건부 UPDATE의 조합으로 충분.
 *                  알림 히스토리 API 필요 시 findByUserIdAndCreatedAtAfter 등 날짜 범위 쿼리 추가.
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /** 사용자 알림 이력 조회 (idx_notifications_user 활용). */
    List<NotificationEntity> findByUserId(Long userId);

    /**
     * 재시도 배치 대상 조회 — status IN (PENDING, RETRYING) AND sent_at IS NULL AND retry_count < maxRetry.
     * Pageable로 배치 크기 제한(외부 API 장애 복구 후 대량 누적 시 OOM 방지).
     */
    @Query("""
            SELECT n FROM NotificationEntity n
            WHERE n.status IN :statuses
              AND n.sentAt IS NULL
              AND n.retryCount < :maxRetry
            ORDER BY n.createdAt ASC
            """)
    List<NotificationEntity> findRetryTargets(
            @Param("statuses") List<Status> statuses,
            @Param("maxRetry") int maxRetry,
            Pageable pageable
    );

    /**
     * RetryJob의 이중 발송 방지용 조건부 RETRYING 전이.
     * WHERE sent_at IS NULL AND status IN (PENDING, RETRYING) 조건 충족 시에만 UPDATE 실행.
     * 반환값 1 = 독점 확보 성공, 0 = 이미 다른 인스턴스가 처리 중 or 발송 완료 → RetryJob이 skip.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE NotificationEntity n
            SET n.status = :retrying, n.retryCount = n.retryCount + 1
            WHERE n.id = :id
              AND n.sentAt IS NULL
              AND n.status IN :statuses
              AND n.retryCount < :maxRetry
            """)
    int markAsRetrying(
            @Param("id") Long id,
            @Param("retrying") Status retrying,
            @Param("statuses") List<Status> statuses,
            @Param("maxRetry") int maxRetry
    );
}
