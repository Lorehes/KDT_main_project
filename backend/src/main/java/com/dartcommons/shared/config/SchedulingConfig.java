package com.dartcommons.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * [목적] @Scheduled 폴링 잡 + spring-retry 외부 호출 백오프 + @Async 비동기 잡(백필)을 활성화.
 * [이유] @EnableScheduling/@EnableRetry/@EnableAsync를 메인 앱이 아닌 전용 설정 클래스로 분리해 관심사 격리.
 * [사이드 임팩트] 이 설정이 없으면 @Scheduled / @Retryable / @Async 메서드가 동작하지 않음.
 *               AOP 프록시 기반 — same-class self-invocation은 작동 안함(Spring 표준 한계).
 *               @Async는 TaskExecutor 미설정 시 SimpleAsyncTaskExecutor 사용(매 호출 새 스레드).
 * [수정 시 고려사항] 운영 부하 클 경우 ThreadPoolTaskExecutor 빈으로 풀 제한 추가.
 *                  재시도 전략은 호출 측 @Retryable 어노테이션에 명시 — 글로벌 디폴트 미사용.
 */
@Configuration
@EnableScheduling
@EnableRetry
@EnableAsync
public class SchedulingConfig {
}
