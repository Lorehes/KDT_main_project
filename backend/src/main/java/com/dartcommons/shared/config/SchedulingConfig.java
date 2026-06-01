package com.dartcommons.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * [목적] @Scheduled 기반 폴링 잡(@DisclosurePollingJob 등)을 활성화하는 설정.
 * [이유] @EnableScheduling을 메인 앱이 아닌 전용 설정 클래스로 분리해 관심사 격리.
 * [사이드 임팩트] 이 설정이 없으면 @Scheduled 메서드가 동작하지 않음.
 * [수정 시 고려사항] TaskExecutor 커스터마이징이 필요하면 SchedulingConfigurer 구현 추가.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
