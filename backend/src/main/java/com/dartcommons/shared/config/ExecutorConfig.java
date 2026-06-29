package com.dartcommons.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
 * [목적] 도메인별 @Async 작업용 ThreadPoolTaskExecutor 풀을 분리 제공.
 *       - analysisExecutor         : 신규 공시 LLM 분석(폴링 SLO 보호, 높은 우선순위)
 *       - analysisBackfillExecutor : 백필 분석(낮은 우선순위, 폴링 풀 보호)
 *       - notificationExecutor     : 알림 발송(분석 SLO와 분리)
 *       - contentFetchExecutor     : DART 본문 fetch — 실시간 리스너 + 백필(disclosure-content-text-fetch Spec)
 * [이유] SchedulingConfig만으로는 SimpleAsyncTaskExecutor가 사용되어 매 호출 새 스레드 → 대량 작업 시 스레드 폭주.
 *       도메인별 풀 분리로 외부 API 응답 지연이 교차 도메인 SLO에 영향을 주지 않도록.
 * [사이드 임팩트] @Async("빈명") 매칭 실패 시 fallback SimpleAsyncTaskExecutor — 빈명 오타 주의.
 *               contentFetchExecutor: DART 일일 호출 한도 보호를 위해 낮은 동시성(max=2).
 *               contentFetchExecutor 큐(300)가 가득 차면 TaskRejectedException — 백필 과부하 방지.
 * [수정 시 고려사항] Cloud LLM 전환 시 analysisExecutor 풀 크기 재조정 필요.
 *                  ThreadPoolTaskExecutor 기본 거절 정책 AbortPolicy(TaskRejectedException).
 *                  CallerRunsPolicy 필요 시 exec.setRejectedExecutionHandler(new CallerRunsPolicy()) 추가.
 */
@Configuration
public class ExecutorConfig {

    /** 신규 공시 이벤트 트리거 분석 — 폴링 SLO(30초) 보호 풀. */
    @Bean(name = "analysisExecutor")
    public TaskExecutor analysisExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("analysis-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }

    /** 백필 분석 — 폴링 풀과 격리, 낮은 우선순위. */
    @Bean(name = "analysisBackfillExecutor")
    public TaskExecutor analysisBackfillExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("analysis-backfill-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(60);
        exec.initialize();
        return exec;
    }

    /** 알림 발송 풀 — AnalysisCompletedEvent AFTER_COMMIT 리스너에서 @Async로 분리.
     *  notification-dispatcher Spec Wave 1. 분석 풀과 격리해 발송 지연이 분석 SLO에 영향 없도록. */
    @Bean(name = "notificationExecutor")
    public TaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("notification-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }

    /**
     * DART 본문 fetch 풀 — DisclosureContentFetchListener(실시간) + DisclosureContentBackfillService(백필) 공용.
     * max=2: DART 일일 호출 한도 보호(한도 실측 전 보수적 제한). 큐=300: 백필 시 순서 보장.
     * awaitTerminationSeconds=60: 진행 중 fetch가 완료될 시간 확보 후 종료.
     */
    @Bean(name = "contentFetchExecutor")
    public TaskExecutor contentFetchExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(300);
        exec.setThreadNamePrefix("content-fetch-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(60);
        exec.initialize();
        return exec;
    }
}
