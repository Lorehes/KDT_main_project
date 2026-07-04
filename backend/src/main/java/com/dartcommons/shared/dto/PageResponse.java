package com.dartcommons.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

import java.util.List;

/*
 * [목적] api_spec §1.4 페이지네이션 응답 envelope — Spring Data Page를 스펙 정의 JSON 구조로 변환.
 * [이유] Spring Data Page의 기본 직렬화는 camelCase(totalElements, totalPages)이나
 *       api_spec은 snake_case + "page" nested 구조({ total_elements, total_pages })를 정의.
 *       FE DisclosurePage 타입과 1:1 대응.
 * [사이드 임팩트] 모든 목록 API(공시, 알림)의 응답 형태가 통일됨. 추가 목록 API도 이 DTO를 사용.
 * [수정 시 고려사항] page_meta 필드명 변경 시 api_spec §1.4 + FE 타입 동시 갱신 필요.
 */
public record PageResponse<T>(
        List<T> content,
        PageMeta page
) {

    public record PageMeta(
            int number,
            int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages")    int  totalPages
    ) {}

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                new PageMeta(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages()
                )
        );
    }
}
