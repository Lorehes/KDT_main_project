package com.dartcommons.user.services;

import com.dartcommons.user.dto.NotificationSettingsRequest;
import com.dartcommons.user.dto.NotificationSettingsResponse;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/*
 * [목적] 알림 설정 조회·변경 서비스 — UserEntity의 notify_* 컬럼 업데이트.
 * [이유] 알림 설정은 UserEntity에 비정규화 저장(join 없이 빠른 조회).
 *       별도 서비스로 분리해 UserService·NotificationDispatcher와 역할 명확화.
 * [사이드 임팩트] notify_enabled=false로 설정해도 이미 큐에 들어간 알림은 발송될 수 있음.
 *               NotificationDispatcher는 발송 직전 사용자 설정을 재확인해야 함(후속 구현 시 주의).
 * [수정 시 고려사항] channel=KAKAO 선택 시 phone_number 등록 여부 사전 검증 추가 가능.
 *                  알림 설정 변경 이력이 필요하면 consent_logs 패턴(INSERT-only)으로 별도 테이블 추가.
 */
@Service
@Transactional
public class NotificationSettingsService {

    private final UserRepository userRepository;

    public NotificationSettingsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationSettingsResponse getSettings(Long userId) {
        UserEntity user = findActiveUser(userId);
        return NotificationSettingsResponse.from(user);
    }

    public NotificationSettingsResponse updateSettings(Long userId, NotificationSettingsRequest request) {
        UserEntity user = findActiveUser(userId);
        user.updateNotifySettings(
                request.channel(),
                request.enabled(),
                request.frequency(),
                request.typeFilter(),
                request.offHoursAllowed()
        );
        return NotificationSettingsResponse.from(user);
    }

    private UserEntity findActiveUser(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"));
    }
}
