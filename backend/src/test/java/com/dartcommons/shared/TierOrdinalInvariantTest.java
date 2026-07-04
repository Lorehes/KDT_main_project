package com.dartcommons.shared;

import com.dartcommons.shared.enums.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] Tier enum의 ordinal 순서 불변식 기계 검증 — SecurityUtils.extractTier가 max(ordinal)에 의존.
 * [이유] Tier 추가/재배열 시 ordinal이 권한 강도를 반영해야 한다는 제약은 주석으로만 존재했음.
 *       컴파일 오류 없이 ordinal이 잘못 배치될 경우 PREMIUM 사용자가 FREE 응답을 받는 묵시적 권한 강등 버그 발생.
 * [사이드 임팩트] 새 Tier 값 추가 시 이 테스트가 실패할 수 있음 — 의도적으로 그렇게 설계됨.
 * [수정 시 고려사항] Tier 추가 시 ordinal 순서를 낮은 권한 → 높은 권한 순으로 유지해야 하며 이 테스트를 갱신해야 함.
 */
class TierOrdinalInvariantTest {

    @Test
    @DisplayName("Tier ordinal 순서 — FREE < PRO < PREMIUM (SecurityUtils.extractTier max(ordinal) 불변식)")
    void tier_ordinalOrder_matchesPrivilegeLevel() {
        assertThat(Tier.FREE.ordinal())
                .as("FREE.ordinal should be less than PRO.ordinal")
                .isLessThan(Tier.PRO.ordinal());
        assertThat(Tier.PRO.ordinal())
                .as("PRO.ordinal should be less than PREMIUM.ordinal")
                .isLessThan(Tier.PREMIUM.ordinal());
    }
}
