package com.dartcommons.infrastructure.dart;

import com.dartcommons.disclosure.dto.RawDisclosureItem;
import com.dartcommons.infrastructure.dart.dto.DartListResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/*
 * [목적] DartClient.fetchList 페이지네이션 종료 조건 단위 검증.
 *       2026-06-04 운영 사고 재발 방지 — chunk 12에서 pageNo=10,000+ stuck.
 * [이유] DART list.json이 total_page 초과 시 마지막 페이지를 반복 반환하는 케이스 관측됨.
 *       종료 조건 3가지(total_page 도달 / PAGE_SIZE 미만 / MAX_PAGES 가드)를 각각 검증.
 * [사이드 임팩트] DartPageFetcher를 mock — 외부 호출 없이 페이지네이션 로직만 검증.
 * [수정 시 고려사항] DartClient.MAX_PAGES 값 변경 시 본 테스트도 동기 갱신.
 *                  실제 DART API 응답 동작 확인은 별도 통합 테스트(외부 호출) 영역.
 */
class DartClientPaginationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 2, 18);
    private static final LocalDate TO = LocalDate.of(2026, 5, 18);

    @Test
    @DisplayName("종료 조건 1순위: total_page 도달 시 반복 호출 없이 종료")
    void stopsAtTotalPage() {
        DartClient.DartPageFetcher fetcher = mock(DartClient.DartPageFetcher.class);
        DartClient client = new DartClient(fetcher);

        // total_page=3 가 응답에 들어있고, 각 페이지에 PAGE_SIZE만큼 — 종료 1순위는 total_page
        when(fetcher.fetchPage(any(), any(), anyInt()))
                .thenAnswer(inv -> fullPage(inv.getArgument(2), 3));

        List<RawDisclosureItem> result = client.fetchList(FROM, TO);

        assertThat(result).hasSize(300);
        verify(fetcher, times(3)).fetchPage(any(), any(), anyInt());
    }

    @Test
    @DisplayName("종료 조건 2순위: total_page 누락 응답이라도 PAGE_SIZE 미만이면 종료")
    void stopsAtPartialPageWhenTotalPageMissing() {
        DartClient.DartPageFetcher fetcher = mock(DartClient.DartPageFetcher.class);
        DartClient client = new DartClient(fetcher);

        when(fetcher.fetchPage(any(), any(), eq(1))).thenReturn(fullPage(1, null));
        when(fetcher.fetchPage(any(), any(), eq(2))).thenReturn(partialPage(2, null, 40));

        List<RawDisclosureItem> result = client.fetchList(FROM, TO);

        assertThat(result).hasSize(140);
        verify(fetcher, times(2)).fetchPage(any(), any(), anyInt());
    }

    @Test
    @DisplayName("절대 상한 가드: total_page 신뢰 불가 + 매 페이지 PAGE_SIZE 반환 시 MAX_PAGES에서 강제 종료")
    void stopsAtMaxPagesGuard() {
        DartClient.DartPageFetcher fetcher = mock(DartClient.DartPageFetcher.class);
        DartClient client = new DartClient(fetcher);

        // 운영 사고 시뮬레이션: total_page=null + 매 페이지 PAGE_SIZE 반환 (마지막 페이지 무한 반복 케이스)
        when(fetcher.fetchPage(any(), any(), anyInt()))
                .thenAnswer(inv -> fullPage(inv.getArgument(2), null));

        List<RawDisclosureItem> result = client.fetchList(FROM, TO);

        // MAX_PAGES=3000 에서 강제 종료 — 300,000 항목 한계
        assertThat(result).hasSize(300_000);
        verify(fetcher, times(3000)).fetchPage(any(), any(), anyInt());
    }

    @Test
    @DisplayName("status=013(no data): 1회 호출 후 빈 결과 반환")
    void stopsOnNoDataStatus() {
        DartClient.DartPageFetcher fetcher = mock(DartClient.DartPageFetcher.class);
        DartClient client = new DartClient(fetcher);

        when(fetcher.fetchPage(any(), any(), anyInt())).thenReturn(
                new DartListResponse("013", "no data", 0, 1, 0, 0, List.of())
        );

        List<RawDisclosureItem> result = client.fetchList(FROM, TO);

        assertThat(result).isEmpty();
        verify(fetcher, times(1)).fetchPage(any(), any(), anyInt());
    }

    // --- fixtures ---

    private static DartListResponse fullPage(int pageNo, Integer totalPage) {
        List<DartListResponse.Item> items = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            int idx = (pageNo - 1) * 100 + i;
            items.add(new DartListResponse.Item(
                    "Y", "삼성전자", "00000000", "005930",
                    "보고서_" + idx, String.format("%014d", idx),
                    "filer", "20260301", null));
        }
        return new DartListResponse("000", "ok",
                totalPage == null ? null : totalPage * 100, pageNo, 100, totalPage, items);
    }

    private static DartListResponse partialPage(int pageNo, Integer totalPage, int size) {
        List<DartListResponse.Item> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(new DartListResponse.Item(
                    "Y", "삼성전자", "00000000", "005930",
                    "보고서_p" + pageNo + "_" + i, String.format("%014d", (pageNo - 1) * 100 + i),
                    "filer", "20260301", null));
        }
        return new DartListResponse("000", "ok", null, pageNo, size, totalPage, items);
    }
}
