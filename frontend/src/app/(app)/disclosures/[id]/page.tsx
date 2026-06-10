"use client";

// [목적] 공시 상세 페이지(D3/m04·D17/m17·D18/m20) — Free·Pro·Premium 단계별 분析 + 피드백
// [이유] 티어별 정보 노출: Free(판정+요약), Pro(유사사례+주가반응), Premium(재무+업황). 티어 미달 시 TierGate
// [사이드 임팩트] disclosure 로드 완료 후 analysis 쿼리 활성(R7) — 직렬화로 미스매치 방지.
//   analysis null → disclosure.sentiment 폴백 대신 "분析 대기 중" 배지(R1, 자본시장법 §11.1).
// [수정 시 고려사항] 원문 인용 필드(corp_name·report_nm·수치)는 LLM 변형 없이 그대로 렌더(CLAUDE.md §4).
//   is_withheld=true 또는 confidence<0.5 시 SentimentBadge를 WITHHELD로 표시(투자자 보호 의무).
//   Premium financial_context는 현재 JSON 원시 출력 — 구조화된 테이블 UI로 교체 예정

import { useParams } from "next/navigation";
import Link from "next/link";
import { ExternalLink, ArrowLeft } from "lucide-react";
import { useDisclosure, useDisclosureAnalysis, EXPECTED_REACTION_CONFIG } from "@/lib/api/disclosures";
import { useTierCheck } from "@/lib/hooks/useTierCheck";
import { SUPPORT_EMAIL } from "@/lib/constants";
import { SentimentBadge } from "@/components/domain/SentimentBadge";
import { ConfidenceMeter } from "@/components/domain/ConfidenceMeter";
import { DisclaimerNotice } from "@/components/domain/DisclaimerNotice";
import { TierGate } from "@/components/domain/TierGate";
import { FeedbackPrompt } from "@/components/domain/FeedbackPrompt";
import { PriceReactionChart } from "@/components/domain/PriceReactionChart";

export default function DisclosureDetailPage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const { isPro, isPremium } = useTierCheck();

  const { data: disclosure, isLoading: discLoading } = useDisclosure(id);
  // R7: disclosure 로드 완료 후 분析 쿼리 활성 — 미스매치 데이터 렌더 방지
  const { data: analysis, isLoading: analysisLoading } = useDisclosureAnalysis(id, { enabled: !!disclosure });

  if (discLoading || analysisLoading) {
    return (
      <div className="flex h-64 items-center justify-center text-sm text-muted-foreground" role="status" aria-live="polite">
        공시 정보를 불러오는 중...
      </div>
    );
  }

  if (!disclosure) {
    return (
      <div className="flex h-64 flex-col items-center justify-center gap-4 text-sm text-muted-foreground">
        <p>공시를 찾을 수 없습니다.</p>
        <Link href="/disclosures" className="font-bold text-primary hover:underline">← 공시 피드로 돌아가기</Link>
      </div>
    );
  }

  // is_withheld=true 또는 confidence<0.5 → WITHHELD (투자자 보호 — CLAUDE.md §6-6)
  const isWithheld = (analysis?.is_withheld ?? false) ||
    (analysis?.confidence !== undefined && analysis.confidence < 0.5);
  // R1: analysis 미완료 시 disclosure.sentiment(룰 기반) 노출 금지 — 자본시장법 §11.1
  // analysis가 존재할 때만 폴백 허용. null이면 undefined → SentimentBadge 미표시
  const sentiment = analysis ? (analysis.sentiment ?? disclosure.sentiment) : undefined;
  const reactionCfg = analysis?.expected_reaction
    ? EXPECTED_REACTION_CONFIG[analysis.expected_reaction] ?? EXPECTED_REACTION_CONFIG.FLAT
    : null;

  return (
    <div className="mx-auto max-w-4xl">
      <Link href="/disclosures" className="mb-5 inline-flex items-center gap-1.5 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
        <ArrowLeft className="size-4" aria-hidden />
        공시 피드
      </Link>

      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        {/* ── 메인 컨텐츠 ── */}
        <div className="flex flex-col gap-5">

          {/* 헤더 — 회사명·코드·제목은 DART 원본 그대로 렌더 (LLM 변형 금지) */}
          <div className="rounded-2xl border border-border bg-card p-6 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xl font-extrabold text-foreground">{disclosure.corp_name}</span>
                  <span className="font-mono text-sm text-muted-foreground">{disclosure.stock_code}</span>
                </div>
                <p className="mt-1.5 text-base text-foreground">{disclosure.report_nm}</p>
                <time className="mt-1 block text-xs text-muted-foreground" dateTime={disclosure.rcept_dt}>
                  접수일 {disclosure.rcept_dt}
                </time>
              </div>
              {sentiment
                ? <SentimentBadge sentiment={sentiment} isWithheld={isWithheld} />
                : (
                  // R1: analysis 미완료 → 룰 기반 sentiment 대신 "분析 대기 중" 명시 (자본시장법 §11.1)
                  <span
                    className="inline-flex items-center rounded-full border border-border bg-muted px-3 py-1 text-xs font-bold text-muted-foreground"
                    role="status"
                    aria-label="AI 분석 대기 중"
                  >
                    분석 대기 중
                  </span>
                )
              }
            </div>

            {analysis?.confidence !== undefined && (
              <div className="mt-4 border-t border-border pt-4">
                <ConfidenceMeter confidence={analysis.confidence} />
              </div>
            )}
          </div>

          {/* Free — AI 분석 요약 */}
          {analysis && (
            <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="summary-heading">
              <h2 id="summary-heading" className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">AI 분석 요약</h2>
              {isWithheld ? (
                <p className="text-sm text-muted-foreground">AI 신뢰도가 낮아 판단을 보류합니다. DART 원문을 직접 확인하시기 바랍니다.</p>
              ) : (
                <>
                  <p className="text-[15px] leading-relaxed text-foreground">{analysis.summary}</p>
                  {reactionCfg && (
                    <div className={`mt-4 inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-bold ${reactionCfg.colorClass}`}>
                      {reactionCfg.label}
                      {analysis.rationale && <span className="font-normal text-muted-foreground"> · {analysis.rationale}</span>}
                    </div>
                  )}
                </>
              )}
            </section>
          )}

          {/* Pro — 유사 공시 + 주가 반응 차트 */}
          <section aria-labelledby="pro-heading">
            <h2 id="pro-heading" className="mb-3 flex items-center gap-2 text-[11px] font-extrabold uppercase tracking-widest text-primary">
              과거 유사 공시 반응
              <span className="rounded-md bg-primary px-1.5 py-0.5 text-[10px] text-primary-foreground">Pro</span>
            </h2>
            {isPro && analysis?.similar_disclosures ? (
              <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
                <PriceReactionChart data={analysis.similar_disclosures} />
                <ul className="mt-4 divide-y divide-border" aria-label="유사 공시 목록">
                  {analysis.similar_disclosures.map((s) => (
                    <li key={s.rcept_no} className="flex items-center justify-between py-3">
                      <div>
                        <p className="text-sm font-bold text-foreground">{s.corp_name}</p>
                        <time className="font-mono text-xs text-muted-foreground">{s.rcept_dt}</time>
                      </div>
                      <span className={`font-mono text-sm font-extrabold ${s.price_reaction_5d_pct > 0 ? "text-[color:var(--color-sentiment-positive)]" : "text-[color:var(--color-sentiment-negative)]"}`}>
                        {s.price_reaction_5d_pct > 0 ? "+" : ""}{s.price_reaction_5d_pct.toFixed(1)}%
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : !isPro ? (
              <TierGate requiredTier="PRO">
                <div className="h-40 rounded-xl bg-muted/40 p-4 text-xs text-muted-foreground">과거 주가 반응 차트 (Pro 전용)</div>
              </TierGate>
            ) : (
              <p className="text-sm text-muted-foreground">유사 공시 데이터가 없습니다.</p>
            )}
          </section>

          {/* Premium — 재무 영향 + 업황 */}
          <section aria-labelledby="premium-heading">
            <h2 id="premium-heading" className="mb-3 flex items-center gap-2 text-[11px] font-extrabold uppercase tracking-widest text-primary">
              재무·업황 심층 분석
              <span className="rounded-md bg-[color:var(--color-sentiment-withheld)] px-1.5 py-0.5 text-[10px] text-[color:var(--color-sentiment-withheld-foreground)]">Premium</span>
            </h2>
            {isPremium && analysis?.financial_context ? (
              <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
                {/* W7에서 구조화된 재무 테이블·업황 비교 UI로 교체 예정 */}
                <pre className="whitespace-pre-wrap text-sm text-foreground">
                  {JSON.stringify(analysis.financial_context, null, 2)}
                </pre>
              </div>
            ) : !isPremium ? (
              <TierGate requiredTier="PREMIUM">
                <div className="h-36 rounded-xl bg-muted/40 p-4 text-xs text-muted-foreground">재무 영향 지표 · 업황 비교 (Premium 전용)</div>
              </TierGate>
            ) : null}
          </section>

          {/* 피드백 — 모든 공시 상세에 상시 노출 의무 (CLAUDE.md §6-6) */}
          {analysis && (
            <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="feedback-heading">
              <h2 id="feedback-heading" className="mb-4 text-[11px] font-extrabold uppercase tracking-widest text-primary">분석 피드백</h2>
              <FeedbackPrompt analysisId={analysis.analysis_id} />
            </section>
          )}
        </div>

        {/* ── 사이드바 (웹 전용) ── */}
        <aside className="hidden lg:flex lg:flex-col lg:gap-4">
          {disclosure.attachment_url && (
            <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
              <p className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">원문</p>
              <a href={disclosure.attachment_url} target="_blank" rel="noopener noreferrer"
                className="flex items-center gap-2 text-sm font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                aria-label="DART 원문 열기 (새 탭)">
                <ExternalLink className="size-4" aria-hidden />
                DART 공시 원문 보기
              </a>
            </div>
          )}

          {/* 면책 고지 — 모든 분석 화면 상시 노출 필수 (CLAUDE.md §6-6) */}
          <DisclaimerNotice reportPath={analysis?.report_inaccuracy_path ?? `mailto:${SUPPORT_EMAIL}`} />

          {analysis && (
            <div className="rounded-2xl border border-border bg-card p-5 shadow-sm text-xs text-muted-foreground">
              <p className="mb-2 font-extrabold uppercase tracking-widest text-primary">분석 정보</p>
              <dl className="flex flex-col gap-1.5">
                <div className="flex justify-between"><dt>분석 단계</dt><dd className="font-bold text-foreground">Stage {analysis.stage_reached}</dd></div>
                <div className="flex justify-between"><dt>분석 시각</dt><dd className="font-bold text-foreground">{new Date(analysis.created_at).toLocaleString("ko-KR")}</dd></div>
              </dl>
            </div>
          )}
        </aside>
      </div>

      {/* 모바일 면책 고지 — analysis 없어도 신고 경로 표시(CLAUDE.md §6-6 신고 경로 동반 의무) */}
      <div className="mt-6 lg:hidden">
        <DisclaimerNotice reportPath={analysis?.report_inaccuracy_path ?? `mailto:${SUPPORT_EMAIL}`} />
      </div>
    </div>
  );
}
