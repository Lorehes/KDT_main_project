package com.dartcommons.shared.ports;

import java.util.List;

/*
 * [목적] user 도메인의 포트폴리오 종목코드 정보를 다른 도메인에 제공하는 읽기 전용 포트 인터페이스.
 * [이유] disclosure/analysis 도메인이 user 도메인 패키지를 직접 import하면 CLAUDE.md §3-2 도메인 경계를 위반함.
 *       shared/ports에 인터페이스를 두어 import 방향을 `user → shared`, `disclosure/analysis → shared`로 고정.
 *       구현체(UserStockCodesPortImpl)는 user/services에 위치해 PortfolioRepository를 캡슐화.
 * [사이드 임팩트] disclosure.DisclosureQueryService·analysis.FeedbackService는 이 포트만 의존 — user 도메인 패키지 미참조.
 *               모든 호출부에서 userId는 반드시 SecurityContext(@AuthenticationPrincipal)에서 추출해야 함.
 *               요청 파라미터(경로/쿼리)에서 직접 바인딩한 userId 전달은 IDOR 위험 — 호출 시 주의.
 * [수정 시 고려사항] 메서드 추가 시 구현체(UserStockCodesPortImpl) 동시 갱신 필요.
 *                  수평 확장 이후 캐시(Caffeine/Redis) 레이어 추가 시 이 인터페이스에 캐시 어댑터를 삽입.
 */
public interface UserStockCodesPort {

    /**
     * userId가 보유한 종목코드 목록을 반환. 포트폴리오가 없으면 빈 List.
     * <p><b>주의:</b> userId는 반드시 {@link org.springframework.security.core.annotation.AuthenticationPrincipal}에서
     * 추출한 인증된 주체여야 합니다. 요청 파라미터에서 바인딩하면 IDOR 위험이 있습니다.</p>
     */
    List<String> getStockCodes(Long userId);

    /**
     * userId의 포트폴리오에 stockCode가 포함돼 있는지 확인.
     * disclosure.hasPortfolioAccess() — IDOR 방어 게이트.
     */
    boolean hasStockCode(Long userId, String stockCode);
}
