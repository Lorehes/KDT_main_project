// Next 15: params 는 Promise (async 컴포넌트에서 await)
export default async function DisclosureDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return (
    <main className="mx-auto max-w-2xl p-8">
      <h1 className="text-2xl font-bold">공시 상세</h1>
      <p className="mt-2 text-muted-foreground">
        준비 중 — 공시 #{id} 헤더/요약/근거 + 티어별 분석(통합기획서 4.5). 분석에는 신뢰도·면책·신고
        경로 동반.
      </p>
    </main>
  );
}
