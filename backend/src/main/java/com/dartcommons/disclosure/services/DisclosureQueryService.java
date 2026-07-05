package com.dartcommons.disclosure.services;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.disclosure.dto.DisclosureListItemResponse;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.shared.config.PricingProperties;
import com.dartcommons.shared.dto.PageResponse;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.shared.enums.Tier;
import com.dartcommons.shared.ports.UserStockCodesPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/*
 * [목적] GET /api/v1/disclosures 목록 + GET /api/v1/disclosures/{id} 상세 조회 서비스.
 *       + 분析 결과 포트폴리오 소유권 검증(hasPortfolioAccess) — IDOR 방어 게이트.
 * [이유] 공시 피드는 보유 종목 필터(scope=portfolio)가 기본값 — UserStockCodesProvider 인터페이스를 통해
 *       user 도메인과의 결합을 최소화(CLAUDE.md §3-2 도메인 경계 준수).
 *       분析 결과(sentiment/confidence)를 N+1 없이 bulk 조회해 목록 응답에 포함.
 *       hasPortfolioAccess(): 분析 엔드포인트 호출 전 컨트롤러에서 사전 검증 — 미소유 시 404(정보 누설 방지).
 * [사이드 임팩트] PortfolioRepository 직접 의존 제거 — UserStockCodesProvider 인터페이스로 격리(R4).
 *               sentiment 필터를 DB 레벨 native query JOIN으로 처리 — totalElements/totalPages 정확도 개선.
 *               scope=all은 Free 사용자 차단 — Pro 이상만 전체 공시 피드 열람 가능.
 *               Free 티어: fromDate/toDate를 최근 5일(Asia/Seoul, 오늘 포함) 창으로 경계 클램프
 *               + page=0 + size≤5 강제(portfolios-recent-disclosures-5d — dashboard-real-data R3의 "오늘 강제"에서 완화).
 *               날짜 미지정 Free 호출(예: disclosures 피드)의 조회 범위가 오늘 → 최근 5일로 넓어짐 — 의도된 완화.
 * [수정 시 고려사항] stock_code 파라미터와 scope=portfolio 동시 지정 시 교집합 필터로 처리.
 *                  stockCodes null 분기(scope=all)는 findAllFilteredWithSentiment, non-null은 findFilteredByStocksWithSentiment.
 *                  ids.isEmpty() 가드: Hibernate가 IN()을 SQL 오류로 변환하므로 빈 컬렉션일 때 DB 호출 생략.
 *                  sentimentStr: Sentiment enum → name() 변환 후 native query에 전달. null → 전체 반환.
 *                  size 이중 방어: 컨트롤러 @Max(100) + 서비스 진입부 Math.min — AOP 우회(직접 호출 등) 방어선.
 *                  Free 강제 블록은 scope=all 403 분기 이후에 위치 — scope=all FREE는 이미 차단됨.
 *                  Free 클램프는 창 밖 값만 안쪽으로 당기는 방식 — from=to=오늘을 명시 전송하는 dashboard 결과 불변.
 *                  클램프 후에도 from>to 역전 가능(예: from=미래 날짜) — 빈 결과 반환이 정상 동작(테스트로 고정).
 *                  Free 창 일수는 PricingProperties(FREE plan recentWindowDays, yml=5)에서 파생 — 티어 날짜 정책
 *                  단일 소스(tier-policy-config-api). config 누락/0이면 DEFAULT_FREE_WINDOW_DAYS로 안전 폴백.
 *                  창 값 변경 시 [[dashboard-recent-3days]]의 3일 창(⊂Free창 전제)과 통합 테스트도 함께 검토.
 *                  q 빈 문자열 → null 정규화는 서비스 진입부에서 처리 — 컨트롤러 검증 레이어와 책임 분리.
 *                  q는 Free 티어 날짜·size 강제 이후에도 그대로 전달 — 날짜 범위 내 키워드 검색.
 */
@Service
@RequiredArgsConstructor
public class DisclosureQueryService {

    /** Free 창 config 누락/0 시 안전 폴백(오늘 포함 일수). PricingProperties FREE plan이 정상이면 미사용. */
    private static final int DEFAULT_FREE_WINDOW_DAYS = 5;

    private final DisclosureRepository      disclosureRepository;
    private final AnalysisResultRepository  analysisResultRepository;
    private final UserStockCodesPort        userStockCodesProvider;
    private final PricingProperties         pricingProperties;

    /** FREE plan의 recentWindowDays(날짜 축 정책)를 반환. config 누락/0이면 안전 폴백. */
    private int freeWindowDays() {
        return pricingProperties.plans().stream()
                .filter(p -> "FREE".equalsIgnoreCase(p.tier()))
                .map(PricingProperties.Plan::recentWindowDays)
                .filter(d -> d > 0)
                .findFirst()
                .orElse(DEFAULT_FREE_WINDOW_DAYS);
    }

    @Transactional(readOnly = true)
    public PageResponse<DisclosureListItemResponse> list(
            Long userId,
            String scope,
            String stockCode,
            Sentiment sentimentFilter,
            Boolean withheldFilter,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size,
            Tier tier,
            String q
    ) {
        // 빈 문자열 → null 정규화 (R6: "" = 필터 없음). 로컬 변수로 분리 — 파라미터 재할당 금지(Google Java Style).
        String normalizedQ = (q != null && q.isBlank()) ? null : q;
        // 컨트롤러 @Max(100) 우회 경로(테스트/직접 서비스 호출) 대비 이중 방어
        size = Math.min(size, 100);

        // scope=all은 Pro+ 전용 — 단일 stockCode 필터와 다른 "전체 피드" BM 기능
        if ("all".equalsIgnoreCase(scope)
                && (stockCode == null || stockCode.isBlank())
                && tier == Tier.FREE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "scope=all은 Pro 이상 플랜에서 사용 가능합니다.");
        }

        // Free 티어: 최근 5일(Asia/Seoul, 오늘 포함) 경계 클램프 + page=0 + 최대 5건 강제
        // (portfolios-recent-disclosures-5d — "오늘 강제"에서 완화. 건수 축 BM 정책은 유지)
        // 창 밖 값만 안쪽으로 당김 — 창 안의 명시 요청(예: dashboard from=to=오늘)은 그대로 통과
        // scope=all FREE는 위 403에서 이미 차단됨 — 여기는 scope=portfolio 또는 stockCode 단건 경로
        if (tier == Tier.FREE) {
            LocalDate seoulToday = LocalDate.now(ZoneId.of("Asia/Seoul"));
            LocalDate windowStart = seoulToday.minusDays(freeWindowDays() - 1L);
            fromDate = (fromDate == null || fromDate.isBefore(windowStart)) ? windowStart : fromDate;
            toDate   = (toDate == null || toDate.isAfter(seoulToday)) ? seoulToday : toDate;
            page = 0;
            size = Math.min(size, 5);
        }

        List<String> stockCodes = resolveStockCodes(userId, scope, stockCode);

        // 포트폴리오 종목이 없으면 빈 페이지 즉시 반환
        if (stockCodes != null && stockCodes.isEmpty()) {
            return PageResponse.from(Page.empty(PageRequest.of(page, size)));
        }

        // Pageable: Sort 없이 — native/JPQL ORDER BY와 이중 적용 방지
        Pageable pageable = PageRequest.of(page, size);
        // 보류(is_withheld)는 sentiment 값이 아닌 별도 플래그(disclosure-withheld-filter).
        // 보류 필터 시 sentiment 무시(null)하고 is_withheld=true만 — 4상태(호재/중립/악재/보류) 일관.
        // 감성 필터는 기존 동작 유지(withheld=null → 보류 포함). 둘 다 없으면 JOIN 없는 JPQL 경로로 성능 보존.
        boolean withheldOnly = Boolean.TRUE.equals(withheldFilter);
        String sentimentStr = withheldOnly ? null : (sentimentFilter != null ? sentimentFilter.name() : null);
        Boolean withheldParam = withheldOnly ? Boolean.TRUE : null;

        Page<Disclosure> disclosurePage;
        if (sentimentStr != null || withheldParam != null) {
            disclosurePage = (stockCodes == null)
                    ? disclosureRepository.findAllFilteredWithSentiment(fromDate, toDate, sentimentStr, withheldParam, normalizedQ, pageable)
                    : disclosureRepository.findFilteredByStocksWithSentiment(stockCodes, fromDate, toDate, sentimentStr, withheldParam, normalizedQ, pageable);
        } else {
            disclosurePage = (stockCodes == null)
                    ? disclosureRepository.findAllFiltered(fromDate, toDate, normalizedQ, pageable)
                    : disclosureRepository.findFilteredByStocks(stockCodes, fromDate, toDate, normalizedQ, pageable);
        }

        // bulk 분析 결과 조회 (N+1 방지) — 빈 IN() 오류 방지를 위해 ids가 비어있으면 생략
        List<Long> ids = disclosurePage.getContent().stream()
                .map(Disclosure::getId).toList();
        Map<Long, AnalysisResult> analysisMap = ids.isEmpty()
                ? Map.of()
                : analysisResultRepository.findByDisclosureIdIn(ids)
                        .stream()
                        .collect(Collectors.toMap(AnalysisResult::getDisclosureId, ar -> ar));

        // 메모리 sentiment 필터 제거 — DB 쿼리에서 처리됨, page 메타 정확
        List<DisclosureListItemResponse> content = disclosurePage.getContent().stream()
                .map(d -> DisclosureListItemResponse.from(d, analysisMap.get(d.getId())))
                .toList();

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
     * 공시 분析 결과 접근 전 포트폴리오 소유권 검증 게이트.
     * disclosure.stockCode가 userId 포트폴리오에 포함돼 있을 때만 true 반환.
     * 비상장(stockCode == null) 또는 공시 미존재 시 false — 호출자는 404 반환(정보 누설 방지).
     */
    @Transactional(readOnly = true)
    public boolean hasPortfolioAccess(Long userId, Long disclosureId) {
        String stockCode = disclosureRepository.findStockCodeById(disclosureId).orElse(null);
        if (stockCode == null) return false;
        return userStockCodesProvider.hasStockCode(userId, stockCode);
    }

    /** scope/stockCode 파라미터로 필터링할 종목코드 목록을 결정. null = 전체(scope=all). */
    private List<String> resolveStockCodes(Long userId, String scope, String stockCode) {
        if (stockCode != null && !stockCode.isBlank()) {
            return List.of(stockCode);
        }
        if (!"all".equalsIgnoreCase(scope)) {
            // scope=portfolio(기본) — 사용자 보유 종목만
            return userStockCodesProvider.getStockCodes(userId);
        }
        return null; // scope=all → stockCodes=null → 전체 조회
    }
}
