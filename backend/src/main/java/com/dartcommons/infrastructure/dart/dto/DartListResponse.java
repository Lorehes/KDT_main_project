package com.dartcommons.infrastructure.dart.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/*
 * [목적] DART OpenAPI list.json 응답을 파싱하는 DTO. api_spec.md §3.1 계약 준수.
 * [이유] RestClient가 역직렬화할 때 DART의 snake_case 필드를 Java camelCase record로 매핑.
 *       스키마 파싱 없이 직접 사용하면 환각 데이터가 혼입될 수 있으므로(CLAUDE.md §6-6) record로 강제.
 * [사이드 임팩트] DART 응답 스키마가 바뀌면 역직렬화 실패 → 폴링 잡이 예외를 잡아 스킵.
 * [수정 시 고려사항] DART list.json에 필드가 추가되면 이 record에 nullable 필드로 추가.
 *                  corp_name·report_nm은 DART 원본 그대로 보존(LLM 변형 금지, CLAUDE.md §4).
 *                  total_count·page_count가 null이면 단일 페이지 처리.
 */
public record DartListResponse(
        @JsonProperty("status") String status,
        @JsonProperty("message") String message,
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("page_no") Integer pageNo,
        @JsonProperty("page_count") Integer pageCount,
        @JsonProperty("total_page") Integer totalPage,
        @JsonProperty("list") List<Item> list
) {

    /** DART 응답 status 코드 상수 (api_spec.md §3.1) */
    public static final String STATUS_OK = "000";
    public static final String STATUS_NO_DATA = "013";
    public static final String STATUS_KEY_ERROR = "020";
    public static final String STATUS_PARAM_ERROR = "100";
    public static final String STATUS_SYSTEM_ERROR_800 = "800";
    public static final String STATUS_SYSTEM_ERROR_900 = "900";

    /** null-safe list 접근 */
    public List<Item> safeList() {
        return list == null ? Collections.emptyList() : list;
    }

    public boolean isOk() {
        return STATUS_OK.equals(status);
    }

    public boolean isNoData() {
        return STATUS_NO_DATA.equals(status);
    }

    /*
     * [목적] DART list.json의 개별 공시 항목. disclosures 테이블 INSERT 전 Stage 1 룰 추출 원재료.
     * [이유] DART 원본 필드를 String 그대로 보존 — LLM/서비스 계층이 변형하기 전 읽기 전용 DTO.
     * [사이드 임팩트] stockCode는 비상장 시 공백 문자열("      ") → trim() 후 blank 확인 필요.
     * [수정 시 고려사항] rceptDt는 "YYYYMMDD" 형식. DisclosureCollectionService에서 LocalDate.parse 필요.
     */
    public record Item(
            @JsonProperty("corp_cls") String corpCls,
            @JsonProperty("corp_name") String corpName,
            @JsonProperty("corp_code") String corpCode,
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("report_nm") String reportNm,
            @JsonProperty("rcept_no") String rceptNo,
            @JsonProperty("flr_nm") String flrNm,
            @JsonProperty("rcept_dt") String rceptDt,
            @JsonProperty("rm") String rm
    ) {
        /** 비상장(공백) 종목코드 정규화 */
        public String stockCodeOrNull() {
            if (stockCode == null || stockCode.isBlank()) return null;
            return stockCode.trim();
        }
    }
}
