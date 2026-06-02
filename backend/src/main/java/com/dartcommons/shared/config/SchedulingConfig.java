package com.dartcommons.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * [목적] @Scheduled 기반 폴링 잡 + spring-retry 기반 외부 호출 백오프를 활성화하는 설정.
 * [이유] @EnableScheduling/@EnableRetry를 메인 앱이 아닌 전용 설정 클래스로 분리해 관심사 격리.
 * [사이드 임팩트] 이 설정이 없으면 @Scheduled / @Retryable 메서드가 동작하지 않음.
 *               @Retryable은 AOP 프록시 기반 — same-class self-invocation 우회는 작동 안함(Spring 표준 한계).
 * [수정 시 고려사항] TaskExecutor 커스터마이징이 필요하면 SchedulingConfigurer 구현 추가.
 *                  재시도 전략은 호출 측 @Retryable 어노테이션에 명시 — 글로벌 디폴트 미사용(명시적 관리).
 */
@Configuration
@EnableScheduling
@EnableRetry
public class SchedulingConfig {
}
