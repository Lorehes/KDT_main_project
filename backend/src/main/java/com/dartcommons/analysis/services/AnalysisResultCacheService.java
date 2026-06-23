package com.dartcommons.analysis.services;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/*
 * [목적] AnalysisResult 캐싱 전담 빈 — GET /disclosures/{id}/analysis DB 반복 조회 절감.
 * [이유] Spring Data JPA Optional 반환 메서드에 @Cacheable 직접 적용 시 Spring Data 프록시가 Optional을 언랩해
 *       SpEL #result 타입이 Optional<T>가 아닌 T로 전달되는 문제가 있음.
 *       별도 @Component 빈에서 null-반환 메서드로 @Cacheable을 적용해 이 문제를 우회.
 *       unless="#result == null" — 미분析 공시(empty → null 변환)는 캐시 제외, 分析 완료 후 DB에서 정상 조회됨.
 * [사이드 임팩트] AnalysisQueryService가 repository 직접 조회 대신 이 빈을 경유함.
 *               DisclosureQueryService.detail() 경로는 여전히 repository 직접 조회(캐시 미적용) — 목록 경로는 무캐시 허용.
 *               AnalysisResult는 Stage 2 완료 후 write-once — TTL(10분) 내 내용 변경 없음, 명시적 evict 불필요.
 * [수정 시 고려사항] 재分析 기능 도입 시 save 완료 후 @CacheEvict(value="analysisResult", key="#result.disclosureId") 추가.
 *                  CacheConfig.java에서 "analysisResult" 캐시 spec(maxSize 10_000, TTL 10분)을 관리.
 */
@Component
@RequiredArgsConstructor
public class AnalysisResultCacheService {

    private final AnalysisResultRepository repository;

    @Cacheable(value = "analysisResult", key = "#disclosureId", unless = "#result == null")
    @Transactional(readOnly = true)
    public AnalysisResult findByDisclosureId(Long disclosureId) {
        return repository.findByDisclosureId(disclosureId).orElse(null);
    }
}
