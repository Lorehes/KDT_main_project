package com.dartcommons.disclosure.services;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.shared.dto.PageResponse;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/*
 * [목적] GET /api/v1/disclosures 목록 + GET /api/v1/disclosures/{id} 상세 조회 서비스.
 *       + 분석 결과 포트폴리오 소유권 검증(hasPortfolioAccess) — IDOR 방어 게이트.
 * [이유] 공시 피드는 보유 종목 필터(scope=portfolio)가 기본값 — PortfolioRepository 직접 의존 허용(read-only, CLAUDE.md §3-2 마스터 데이터 예외 아님이나 user 도메인 read 참조는 MVP 한시 허용).
 *       분석 결과(sentiment/confidence)를 N+1 없이 bulk 조회해 목록 응답에 포함.
 *       hasPortfolioAccess(): 분석 엔드포인트 호출 전 컨트롤러에서 사전 검증 — 미소유 시 404(정보 누설 방지).
 * [사이드 임팩트] PortfolioRepository 의존 — user 도메인 의존성. 추후 이벤트/공유 인터페이스로 격리 가능.
 *               sentiment 파라미터 필터링은 메모리에서 처리 — 페이지 결과가 size보다 적을 수 있음(MVP 허용).
 *               scope=all은 Free 사용자 차단(R4) — Pro 이상만 전체 공시 피드 열람 가능.
 * [수정 시 고려사항] sentiment 필터를 JPQL JOIN으로 처리하면 페이지 카운트 정확도 개선 가능(Stage 3 후속).
 *                  stock_code 파라미터와 scope=portfolio 동시 지정 시 교집합 필터로 처리.
 *                  stockCodes null 분기(scope=all)는 findAllFiltered, non-null은 findFilteredByStocks — 리포지토리 분리 이유는 DisclosureRepository 주석 참고.
 *                  ids.isEmpty() 가드: Hibernate가 IN()을 SQL 오류로 변환하므로 빈 컬렉션일 때 DB 호출 생략.
 */
@Service
@RequiredArgsConstructor
public class DisclosureQueryService {

    private final DisclosureRepository     disclosureRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final PortfolioRepository      portfolioRepository;

    @Transactional(readOnly = true)
    public PageResponse<DisclosureListItemResponse> list(
            Long userId,
            String scope,
            String stockCode,
            Sentiment sentimentFilter,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size,
            UserEntity.Tier tier
    ) {
        // R4: scope=all은 Pro+ 전용 — 단일 stockCode 필터와 다른 "전체 피드" BM 기능
        if ("all".equalsIgnoreCase(scope)
                && (stockCode == null || stockCode.isBlank())
                && tier == UserEntity.Tier.FREE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "scope=all은 Pro 이상 플랜에서 사용 가능합니다.");
        }

        List<String> stockCodes = resolveStockCodes(userId, scope, stockCode);

        // 포트폴리오 종목이 없으면 빈 페이지 즉시 반환
        if (stockCodes != null && stockCodes.isEmpty()) {
            return PageResponse.from(Page.empty(PageRequest.of(page, size)));
        }

        // Sort은 JPQL ORDER BY에 명시 — Pageable에 Sort 미포함으로 이중 적용 방지
        Pageable pageable = PageRequest.of(page, size);
        Page<Disclosure> disclosurePage = (stockCodes == null)
                ? disclosureRepository.findAllFiltered(fromDate, toDate, pageable)
                : disclosureRepository.findFilteredByStocks(stockCodes, fromDate, toDate, pageable);

        // bulk 분석 결과 조회 (N+1 방지) — 빈 IN() 오류 방지를 위해 ids가 비어있으면 생략
        List<Long> ids = disclosurePage.getContent().stream()
                .map(Disclosure::getId).toList();
        Map<Long, AnalysisResult> analysisMap = ids.isEmpty()
                ? Map.of()
                : analysisResultRepository.findByDisclosureIdIn(ids)
                        .stream()
                        .collect(Collectors.toMap(AnalysisResult::getDisclosureId, ar -> ar));

        // sentiment 파라미터 필터링은 메모리에서 처리 (MVP)
        List<DisclosureListItemResponse> content = disclosurePage.getContent().stream()
                .map(d -> DisclosureListItemResponse.from(d, analysisMap.get(d.getId())))
                .filter(r -> sentimentFilter == null || sentimentFilter.equals(r.sentiment()))
                .toList();

        // 메모리 필터링 후 page 메타는 원본 Page 기준 유지 (MVP 허용 범위)
        return new PageResponse<>(content, new PageResponse.PageMeta(
                disclosurePage.getNumber(), disclosurePage.getSize(),
                disclosurePage.getTotalElements(), disclosurePage.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public DisclosureListItemResponse detail(Long id) {
        Disclosure disclosure = disclosureRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "공시를 찾을 수 없습니다."));
        AnalysisResult analysis = analysisResultRepository.findByDisclosureId(id).orElse(null);
        return DisclosureListItemResponse.from(disclosure, analysis);
    }

    /**
     * 공시 분석 결과 접근 전 포트폴리오 소유권 검증 게이트.
     * disclosure.stockCode가 userId 포트폴리오에 포함돼 있을 때만 true 반환.
     * 비상장(stockCode == null) 또는 공시 미존재 시 false — 호출자는 404 반환(정보 누설 방지).
     */
    @Transactional(readOnly = true)
    public boolean hasPortfolioAccess(Long userId, Long disclosureId) {
        String stockCode = disclosureRepository.findStockCodeById(disclosureId).orElse(null);
        if (stockCode == null) return false;
        return portfolioRepository.existsByUserIdAndStockCode(userId, stockCode);
    }

    /** scope/stockCode 파라미터로 필터링할 종목코드 목록을 결정. null = 전체(scope=all). */
    private List<String> resolveStockCodes(Long userId, String scope, String stockCode) {
        if (stockCode != null && !stockCode.isBlank()) {
            return List.of(stockCode);
        }
        if (!"all".equalsIgnoreCase(scope)) {
            // scope=portfolio(기본) — 사용자 보유 종목만
            return portfolioRepository.findByUserId(userId)
                    .stream()
                    .map(p -> p.getStockCode())
                    .toList();
        }
        return null; // scope=all → stockCodes=null → 전체 조회
    }
}
