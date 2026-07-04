package com.dartcommons.shared.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/*
 * [목적] system_configs key-value 조회/저장 리포지토리.
 * [이유] 잡 상태 영속화의 단순 진입점 — 도메인 코드는 SystemConfigStore facade를 사용 권장(직접 의존 최소화).
 * [사이드 임팩트] 모든 도메인 공통 인프라 — shared/config에 위치.
 * [수정 시 고려사항] 캐시(@Cacheable) 도입 시 갱신 후 evict 필요.
 */
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {

    Optional<SystemConfig> findByKey(String key);
}
