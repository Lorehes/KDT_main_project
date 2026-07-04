package com.dartcommons.shared.security;

import com.dartcommons.shared.enums.Tier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Comparator;

/*
 * [목적] SecurityContext에서 Tier를 추출하는 공통 유틸 — 컨트롤러마다 복제된 extractTier() 중복 제거.
 * [이유] DisclosureController·PortfolioController 양쪽에 동일한 extractTier 로직이 private 메서드로 박혀있었음.
 *       신규 컨트롤러 추가 시 재복사 방지를 위해 shared/security로 추출.
 * [사이드 임팩트] 인스턴스 생성 없이 정적 호출 — 스프링 빈이 아니므로 DI 불필요.
 *               JwtAuthenticationFilter에서 ROLE_{TIER} 단일 authority로 세팅한다는 전제에 의존.
 * [수정 시 고려사항] max(ordinal) 전략: ordinal 순서가 권한 강도를 나타낸다는 불변을 유지해야 함
 *                  (Tier.FREE=0, PRO=1, PREMIUM=2). 복수 authority 발급으로 바꿔도 최고 티어 보장.
 *                  향후 Multi-role이 필요하면 이 메서드 시그니처를 확장하거나 전략 패턴으로 교체.
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * Authentication의 authorities에서 ROLE_{TIER} 패턴을 파싱해 가장 높은 Tier를 반환.
     * auth가 null이거나 매핑 불가한 authority만 있으면 FREE를 기본으로 반환.
     */
    public static Tier extractTier(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Tier.FREE;
        }
        return auth.getAuthorities().stream()
                .map(a -> {
                    String authority = a.getAuthority();
                    return authority.startsWith("ROLE_") ? authority.substring(5) : authority;
                })
                .map(s -> {
                    try { return Tier.valueOf(s); }
                    catch (IllegalArgumentException e) { return Tier.FREE; }
                })
                .max(Comparator.comparingInt(Tier::ordinal))
                .orElse(Tier.FREE);
    }
}
