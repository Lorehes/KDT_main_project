package com.dartcommons.infrastructure.dart.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/*
 * [목적] DART OpenAPI fnlttSinglAcnt.json 응답 매핑 record — 단일회사 주요계정 조회.
 *       2024-07-06 실측: status=000 정상 / status=013 데이터없음(미제출·신규상장 등).
 * [이유] 환각 방지(CLAUDE.md §6-6) — LLM 추측 없이 실 API 응답 구조 그대로 record 매핑.
 *       fnlttSinglAcnt 실측 필드(2026-07-06 삼성전자 2024 사업보고서 기준):
 *         fs_div: CFS(연결)/OFS(별도), sj_div: BS(재무상태표)/IS(손익계산서)
 *         account_nm: 자산총계·부채총계·자본총계(BS) / 매출액·영업이익·당기순이익(IS)
 *         thstrm_amount/frmtrm_amount: 콤마 포함 문자열, 미보고 시 "-" 또는 빈 문자열.
 * [사이드 임팩트] DartFinancialClient가 이 record를 직접 파싱.
 *               금융업(은행·보험)은 계정체계 달라 account_nm 매칭 미보장 — 별도 확인 필요.
 * [수정 시 고려사항] DART API 필드명 변경 시 @JsonProperty만 갱신(record 구조는 유지).
 *                  CF(현금흐름표) sj_div 추가 필요 시 DartFinancialClient 필터 수정.
 */
public record DartFinancialResponse(
        String status,
        String message,
        List<FinancialAccountItem> list
) {
    public boolean isOk() { return "000".equals(status); }
    public boolean isNoData() { return "013".equals(status); }

    public record FinancialAccountItem(
            @JsonProperty("corp_code")       String corpCode,
            @JsonProperty("bsns_year")       String bsnsYear,
            @JsonProperty("reprt_code")      String reprtCode,
            @JsonProperty("fs_div")          String fsDiv,       // CFS=연결 OFS=별도
            @JsonProperty("sj_div")          String sjDiv,       // BS=재무상태표 IS=손익계산서
            @JsonProperty("account_nm")      String accountNm,
            @JsonProperty("thstrm_amount")   String thstrmAmount,  // 당기 (콤마 포함 문자열)
            @JsonProperty("frmtrm_amount")   String frmtrmAmount,  // 전기
            @JsonProperty("currency")        String currency
    ) {}
}
