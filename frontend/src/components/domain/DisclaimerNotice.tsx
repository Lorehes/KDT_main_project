// [목적] 분석·알림 화면 하단 면책 고지 + 신고 경로 — 법적 필수 요소
// [이유] 자본시장법 제6조·제17조 미등록 투자자문 리스크 방어. 모든 분석 화면 상시 노출 의무(CLAUDE.md §6-6)
// [사이드 임팩트] 공시 상세·알림 센터·알림 설정 화면 등 분석 결과가 표시되는 모든 곳에 사용
// [수정 시 고려사항] 법무 검토 후 문구 업데이트 시 이 파일만 수정. reportPath가 있으면 신고 링크 표시

interface DisclaimerNoticeProps {
  reportPath?: string;
  className?: string;
}

export function DisclaimerNotice({ reportPath, className }: DisclaimerNoticeProps) {
  return (
    <aside
      className={`rounded-lg border border-border bg-muted/50 px-4 py-3 text-xs leading-relaxed text-muted-foreground ${className ?? ""}`}
      role="note"
      aria-label="투자 면책 고지"
    >
      본 분석은 정보 제공용이며 <strong className="font-semibold text-foreground">투자 자문·권유가 아닙니다</strong>.
      AI 분석은 부정확할 수 있으며 투자 판단과 책임은 이용자에게 있습니다.
      본 서비스는 자본시장법 제6조·제17조상 투자자문업에 해당하지 않습니다.
      {reportPath && (
        <>
          {" "}
          <a
            href={reportPath}
            className="font-semibold text-primary underline underline-offset-2 hover:no-underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            부정확한 분석 신고하기 →
          </a>
        </>
      )}
    </aside>
  );
}
