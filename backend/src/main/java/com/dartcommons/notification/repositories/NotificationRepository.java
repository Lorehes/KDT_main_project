package com.dartcommons.notification.repositories;

import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.entities.NotificationEntity.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/*
 * [목적] notifications 테이블(V6+V15+V18) CRUD + 상태 기반 조회 + RetryJob 전용 조건부 RETRYING 전이 + 읽음 처리 제공.
 *       중복 발송 방어는 DB uq_notification_dedup UNIQUE 제약 경유 — 애플리케이션은 DataIntegrityViolationException 포착.
 * [이유] Wave 1 RetryJob Spec: PENDING/RETRYING 고착 레코드 재발송을 위한 findRetryTargets()와
 *       조건부 UPDATE(markAsRetrying)로 이중 발송 원자성 방어(Tech Review High 리스크 처리).
 *       V18: countByUserIdAndIsReadFalse — TopBar 미읽음 카운트. markAllReadByUserId — 전체 읽음 bulk UPDATE.
 * [사이드 임팩트] findRetryTargets는 idx_notifications_status partial 인덱스(PENDING·RETRYING만 커버) 활용.
 *               markAsRetrying의 UPDATE는 sent_at IS NULL + status IN 조건으로 다른 인스턴스와 경쟁 방지.
 *               findByUserId는 idx_notifications_user 복합 인덱스(user_id, created_at DESC) 활용.
 *               markAllReadByUserId는 idx_notifications_unread partial 인덱스 활용(is_read=FALSE만 UPDATE).
 * [수정 시 고려사항] 대량 재시도 배치는 Pageable + @Lock(PESSIMISTIC_WRITE) 추가 검토.
 *                  다중 인스턴스 배포 시 ShedLock + markAsRetrying 조건부 UPDATE의 조합으로 충분.
 *                  알림 히스토리 API 필요 시 findByUserIdAndCreatedAtAfter 등 날짜 범위 쿼리 추가.
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /**
     * 사용자 알림 전체 조회 — 테스트 직접 assert 및 소규모 유틸 조회용.
     * 프로덕션 API 응답은 반드시 Pageable 오버로드 사용.
     */
    List<NotificationEntity> findByUserId(Long userId);

    /**
     * 사용자 알림 이력 페이지네이션 조회 — idx_notifications_user(user_id, created_at DESC) 활용.
     * Pageable의 Sort로 정렬을 제어하므로 메서드명에 OrderBy 없음(중복 방지).
     */
    Page<NotificationEntity> findByUserId(Long userId, Pageable pageable);

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

    /** 사용자 미읽음 알림 수 — TopBar 벨 뱃지용. idx_notifications_unread partial 인덱스 활용. */
    long countByUserIdAndIsReadFalse(Long userId);

    /**
     * 전체 읽음 처리 bulk UPDATE — N+1 방지.
     * is_read = FALSE 조건으로 이미 읽은 레코드는 UPDATE 제외(idx_notifications_unread 활용).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE NotificationEntity n
            SET n.isRead = true, n.readAt = :now
            WHERE n.userId = :userId AND n.isRead = false
            """)
    int markAllReadByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

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
