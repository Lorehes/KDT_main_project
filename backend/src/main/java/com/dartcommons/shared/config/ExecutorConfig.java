package com.dartcommons.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/*
 * [목적] analysis 도메인의 @Async 작업용 ThreadPoolTaskExecutor 두 종류를 분리 제공
 *       (analysis-stage2-llm Spec Tech Review 결정 3).
 *       - analysisExecutor          : 폴링 트리거 분석(우선순위 높음)
 *       - analysisBackfillExecutor  : 백필 분석(우선순위 낮음, 폴링 SLO 보호)
 * [이유] SchedulingConfig만으로는 SimpleAsyncTaskExecutor가 사용되어 매 호출 새 스레드 → 91k 백필 시 스레드 폭주.
 *       Ollama 단일 인스턴스 RPS 한계 — 풀 분리로 백필이 폴링 큐를 막지 않도록.
 * [사이드 임팩트] @Async("analysisExecutor")/(...Backfill...) 빈명 매칭 필수. 매칭 실패 시 fallback SimpleAsyncTaskExecutor.
 *               큐 크기/풀 크기는 운영 부하 측정 후 application.yml 조정 가능(현재 코드 상수).
 * [수정 시 고려사항] Cloud LLM 전환 시 RPS 한계가 다름 → 풀 크기 재조정. SystemConfig 키로 외부화 후속.
 *                  reject policy CallerRunsPolicy 기본 — 큐 가득 시 호출 스레드(이벤트 리스너)가 직접 실행 → 폴링 지연 트레이드오프.
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
}
