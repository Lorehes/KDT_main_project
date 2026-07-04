package com.dartcommons.shared.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/*
 * [목적] Spring Cache + Caffeine 활성화 (@EnableCaching) 및 캐시별 TTL/maxSize 구성.
 *       application.yml의 'cache.type=caffeine' 선언과 함께 작동하며, 이 Bean이 auto-config를 대체한다.
 * [이유] 캐시마다 TTL 요건이 다름(portfolioStockCodes 5분, analysisResult 10분, stockByCode 4시간).
 *       단일 spec 문자열(spring.cache.caffeine.spec)로는 per-cache 설정이 불가능 → 프로그래밍 구성 선택.
 *       @EnableCaching 위치는 Main 대신 독립 Config에 두어 캐시 설정 변경 시 영향 범위를 이 파일에 국한.
 * [사이드 임팩트] CaffeineCacheManager 빈을 선언하므로 Boot auto-config(CaffeineCacheConfiguration)는 back-off.
 *               등록되지 않은 캐시명을 @Cacheable에 쓰면 CaffeineCacheManager 기본 동작(동적 생성)으로 폴백 —
 *               의도치 않은 무제한 캐시 방지를 위해 registerCustomCache 등록 외 캐시명 사용을 피할 것.
 * [수정 시 고려사항] maxSize/TTL은 운영 모니터링(Actuator + 메모리 사용량) 후 조정.
 *                  새 캐시 추가 시 반드시 이 파일에 registerCustomCache 호출 추가 (누락 시 동적 default 캐시 생성됨).
 *                  분산 환경 전환 시 Redis CacheManager로 교체 — @Cacheable/CacheEvict 어노테이션은 그대로 유지됨.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // 보유 종목 코드 목록 — 피드 요청마다 portfolios SELECT 대체. 5분 TTL, 사용자당 1 entry.
        // CacheEvict: PortfolioService create/delete 시 즉시 무효화(portfolios.UserStockCodesProviderImpl).
        manager.registerCustomCache("portfolioStockCodes",
                Caffeine.newBuilder()
                        .maximumSize(1_000)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .build());

        // 공시 분析 결과 엔티티 — write-once(Stage 2 완료 후 불변). 10분 TTL.
        // 티어별 projection(AnalysisResponse.from)은 캐시 외부에서 수행 — 교차티어 오염 방지.
        manager.registerCustomCache("analysisResult",
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .build());

        // 종목 마스터 단건 캐시 — PortfolioService의 getPortfolio/updatePortfolio/createPortfolio corp_name 조회.
        // StockMasterSyncJob.sync() 완료 후 @CacheEvict(allEntries=true)로 무효화. TTL 4h(분기 갱신 주기 대비).
        manager.registerCustomCache("stockByCode",
                Caffeine.newBuilder()
                        .maximumSize(1_000)
                        .expireAfterWrite(Duration.ofHours(4))
                        .build());

        // 종목 마스터 IN 조회 캐시 — PortfolioService.listPortfolios() bulk corp_name fetch.
        // 키: 입력 Collection을 TreeSet으로 변환 후 toString() → 순서 무관 안정 키 보장.
        manager.registerCustomCache("stocksByCodeIn",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(Duration.ofHours(4))
                        .build());

        return manager;
    }
}
