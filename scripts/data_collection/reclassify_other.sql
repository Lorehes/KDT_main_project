-- reclassify_other.sql
-- [목적] DisclosureTypeClassifier 룰 보강 후 기존 disclosures.disclosure_type='OTHER' 일괄 재분류.
-- [이유] 룰 변경 시 기존 데이터는 자동 갱신되지 않음. 9.2만건 백필 분석 결과 OTHER 61% → 16개 신규 유형으로 회수.
-- [사이드 임팩트] Java DisclosureTypeClassifier의 룰 순서와 키워드 정확히 동일해야 분류 결과 일치.
-- [실행] docker exec -i dartcommons-postgres psql -U dartcommons -d dartcommons < reclassify_other.sql

BEGIN;

-- CASE WHEN 순서는 DisclosureTypeClassifier.RULES 순서와 동일 — 더 구체적 키워드를 먼저 매칭.
-- 정기공시/실적/증자감자/채권은 이미 분류되어 있으므로 OTHER만 대상.
UPDATE disclosures
SET disclosure_type = CASE
    -- 실적/예고
    WHEN report_nm LIKE '%잠정실적%' OR report_nm LIKE '%잠정)실적%' OR report_nm LIKE '%영업(잠정)%' THEN 'EARNINGS_PRELIMINARY'
    WHEN report_nm LIKE '%결산실적공시예고%' THEN 'EARNINGS_FORECAST'
    WHEN report_nm LIKE '%매출액또는손익구조%' THEN 'MATERIAL_EARNINGS_CHANGE'

    -- 증권/채권 발행 (구체→일반)
    WHEN report_nm LIKE '%파생결합사채%' OR report_nm LIKE '%파생결합증권%' THEN 'DERIVATIVE_ISSUANCE'
    WHEN report_nm LIKE '%증권발행실적보고서%' THEN 'SECURITIES_ISSUANCE'
    WHEN report_nm LIKE '%투자설명서%' THEN 'PROSPECTUS'
    WHEN report_nm LIKE '%증권신고서(채무증권)%' OR report_nm LIKE '%채무증권%' THEN 'BOND_ISSUANCE'

    -- 주주·지분
    WHEN report_nm LIKE '%최대주주변경%' OR report_nm LIKE '%최대주주등소유주식변동%' THEN 'MAJOR_SHAREHOLDER_CHANGE'

    -- 주주총회 (의결권/소집/명부)
    WHEN report_nm LIKE '%의결권대리행사권유%' THEN 'PROXY_SOLICITATION'
    WHEN report_nm LIKE '%주주총회%' OR report_nm LIKE '%주주명부폐쇄%' THEN 'SHAREHOLDER_MEETING'

    -- IR/감사/거버넌스
    WHEN report_nm LIKE '%기업설명회%' THEN 'IR_EVENT'
    WHEN report_nm LIKE '%감사보고서%' THEN 'AUDIT_REPORT'
    WHEN report_nm LIKE '%기업지배구조보고서%' THEN 'GOVERNANCE_REPORT'
    WHEN report_nm LIKE '%지속가능경영%' THEN 'ESG_REPORT'
    WHEN report_nm LIKE '%대규모기업집단현황%' THEN 'CONGLOMERATE_DISCLOSURE'

    -- 옵션
    WHEN report_nm LIKE '%주식매수선택권%' THEN 'STOCK_OPTION'

    -- 계약·거래
    WHEN report_nm LIKE '%특수관계인%' OR report_nm LIKE '%동일인등출자계열회사%' THEN 'RELATED_PARTY_TRANSACTION'
    WHEN report_nm LIKE '%채무보증%' THEN 'GUARANTEE_DECISION'

    -- 임원
    WHEN report_nm LIKE '%사외이사의선임%' OR report_nm LIKE '%사외이사의해임%' THEN 'EXECUTIVE_CHANGE'

    -- 정보 공시
    WHEN report_nm LIKE '%풍문또는보도%' OR report_nm LIKE '%보도에대한해명%' THEN 'RUMOR_CLARIFICATION'
    WHEN report_nm LIKE '%투자판단관련주요경영사항%' THEN 'MATERIAL_BUSINESS_MATTER'
    WHEN report_nm LIKE '%지급수단별%' OR report_nm LIKE '%분쟁조정기구%' THEN 'DISPUTE_RESOLUTION'

    ELSE 'OTHER'
END
WHERE disclosure_type = 'OTHER';

-- 영향받은 행 수 확인 (참고)
\echo '재분류 후 disclosure_type 분포:'
SELECT disclosure_type, count(*) FROM disclosures GROUP BY disclosure_type ORDER BY count DESC LIMIT 30;

COMMIT;
