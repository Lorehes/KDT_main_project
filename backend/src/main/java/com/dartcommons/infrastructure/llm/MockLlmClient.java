package com.dartcommons.infrastructure.llm;

import com.dartcommons.analysis.dto.Stage2Output;
import com.dartcommons.analysis.dto.Stage4Output;
import com.dartcommons.analysis.entities.AnalysisResult.ExpectedReaction;
import com.dartcommons.shared.enums.Sentiment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/*
 * [목적] 통합 테스트 + 로컬 개발용 결정론적 LLM 스텁 — Ollama/OpenRouter 없이도 Stage 2/4 검증 가능.
 *       프롬프트에 키워드(호재/악재/중립)가 보이면 그에 맞춰 결정론적 응답 — 테스트 시나리오 안정성.
 * [이유] CLAUDE.md §6-6: 통합 테스트는 Mock DB 금지지만, LLM은 안정성 이유로 Mock 권장.
 *       provider=mock 환경에서 활성화 — 운영은 provider=openrouter.
 * [사이드 임팩트] classifyStage4는 프롬프트 키워드로 UP/FLAT/DOWN 분기 — Stage4Analyzer 단위 테스트가 시나리오 유도.
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
        if (prompt.contains("판단보류시나리오")) {
            return new Stage2Output(Sentiment.NEUTRAL, LOW_CONFIDENCE, "정보가 부족하여 판단을 보류합니다.");
        }
        if (prompt.contains("악재시나리오") || prompt.contains("감자") || prompt.contains("상장폐지")) {
            return new Stage2Output(Sentiment.NEGATIVE, HIGH_CONFIDENCE,
                    "회사 가치에 부정적 영향이 예상되는 공시입니다. 주식 가치 희석 가능성이 있습니다. 신중한 검토가 필요합니다.",
                    List.of("자본 조정 목적의 공시가 접수되었습니다."),
                    List.of(),
                    List.of("주식 가치 희석 가능성", "재무 구조 악화 우려"));
        }
        if (prompt.contains("호재시나리오") || prompt.contains("무상증자") || prompt.contains("자기주식취득")) {
            return new Stage2Output(Sentiment.POSITIVE, HIGH_CONFIDENCE,
                    "주주 가치에 긍정적 영향이 예상되는 공시입니다. 기업의 자신감 신호로 해석됩니다. 시장 반응은 종목별 상이할 수 있습니다.",
                    List.of("주주 환원 성격의 공시가 접수되었습니다."),
                    List.of("주주 가치 제고 기대", "기업의 자신감 신호"),
                    List.of());
        }
        return new Stage2Output(Sentiment.NEUTRAL, new BigDecimal("0.650"),
                "통상적인 공시로 즉각적인 가격 영향은 제한적입니다. 추가 정보를 확인하세요. 참고용으로만 활용하세요.");
    }

    @Override
    public Stage4Output classifyStage4(String prompt) {
        if (prompt.contains("stage4상승시나리오") || prompt.contains("유사공시상승")) {
            return new Stage4Output(ExpectedReaction.UP,
                    "과거 유사 공시 사례에서 단기 주가가 상승한 경향이 확인됩니다. 참고용 정보입니다.", HIGH_CONFIDENCE);
        }
        if (prompt.contains("stage4하락시나리오") || prompt.contains("유사공시하락")) {
            return new Stage4Output(ExpectedReaction.DOWN,
                    "과거 유사 공시 사례에서 단기 주가가 하락한 경향이 확인됩니다. 참고용 정보입니다.", HIGH_CONFIDENCE);
        }
        return new Stage4Output(ExpectedReaction.FLAT,
                "과거 유사 공시 사례에서 뚜렷한 방향성이 나타나지 않습니다. 참고용 정보입니다.", HIGH_CONFIDENCE);
    }
}
