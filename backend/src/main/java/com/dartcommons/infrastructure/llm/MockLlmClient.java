package com.dartcommons.infrastructure.llm;

import com.dartcommons.analysis.dto.Stage2Output;
import com.dartcommons.shared.enums.Sentiment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/*
 * [목적] 통합 테스트 + 로컬 개발용 결정론적 LLM 스텁 — Ollama 없이도 wave 1 검증 가능.
 *       프롬프트에 키워드(호재/악재/중립)가 보이면 그에 맞춰 결정론적 응답 — 테스트 시나리오 안정성.
 * [이유] CLAUDE.md §6-6: 통합 테스트는 Mock DB 금지지만, LLM은 안정성 이유로 Mock 권장(Spec NF4).
 *       provider=mock 환경에서 활성화 — 운영 application.yml은 provider=ollama로 차단.
 * [사이드 임팩트] application.yml에 dartcommons.llm.provider=mock 설정 시 OllamaLlmClient 대신 본 빈 주입.
 *               결정론적 응답이라 신뢰도 임계치/withheld 분기를 테스트 케이스에서 명시적으로 트리거 가능.
 * [수정 시 고려사항] 키워드 분기 확장 시 테스트 의존도 증가 — 최소 케이스 유지.
 *                  실제 LLM 동작 시뮬레이션 의도가 아니라 "결정론적 스텁" — 환각/지연 등은 별도 테스트.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.llm", name = "provider", havingValue = "mock", matchIfMissing = false)
public class MockLlmClient implements LlmClient {

    private static final BigDecimal HIGH_CONFIDENCE = new BigDecimal("0.850");
    private static final BigDecimal LOW_CONFIDENCE  = new BigDecimal("0.300");

    @Override
    public Stage2Output classifyStage2(String prompt) {
        // 결정론적 분기 — 프롬프트 키워드 매칭. 테스트에서 명시적으로 시나리오 유도.
        if (prompt.contains("판단보류시나리오")) {
            return new Stage2Output(Sentiment.NEUTRAL, LOW_CONFIDENCE, "정보가 부족하여 판단을 보류합니다.");
        }
        if (prompt.contains("악재시나리오") || prompt.contains("감자") || prompt.contains("상장폐지")) {
            return new Stage2Output(Sentiment.NEGATIVE, HIGH_CONFIDENCE,
                    "회사 가치에 부정적 영향이 예상되는 공시입니다. 주식 가치 희석 가능성이 있습니다. 신중한 검토가 필요합니다.");
        }
        if (prompt.contains("호재시나리오") || prompt.contains("무상증자") || prompt.contains("자기주식취득")) {
            return new Stage2Output(Sentiment.POSITIVE, HIGH_CONFIDENCE,
                    "주주 가치에 긍정적 영향이 예상되는 공시입니다. 기업의 자신감 신호로 해석됩니다. 시장 반응은 종목별 상이할 수 있습니다.");
        }
        // 기본: 중립 + 중간 신뢰도
        return new Stage2Output(Sentiment.NEUTRAL, new BigDecimal("0.650"),
                "통상적인 공시로 즉각적인 가격 영향은 제한적입니다. 추가 정보를 확인하세요. 참고용으로만 활용하세요.");
    }
}
