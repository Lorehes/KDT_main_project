package com.dartcommons.disclosure.services;

import com.dartcommons.infrastructure.dart.DartApiException;
import com.dartcommons.infrastructure.dart.DartApiProperties;
import com.dartcommons.infrastructure.dart.DartDocumentClient;
import com.dartcommons.infrastructure.dart.DartDocumentParser;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;

/*
 * [목적] 단일 공시 ID를 받아 DART document.xml 본문을 fetch하고 disclosures.content_text를 갱신.
 *       멱등 보장(casUpdateContent CAS → 0 rows면 skip) + 영구 실패 표시(DartApiException → fetchedAt 기록).
 * [이유] DartDocumentClient(@Retryable)와 DartDocumentParser를 조합하는 오케스트레이션이 서비스 계층 책임.
 *       @Retryable은 AOP 프록시로 동작해야 하므로 DartDocumentClient를 직접 호출(cross-bean → 프록시 경유).
 *       DisclosureContentFetchListener(실시간)와 DisclosureContentBackfillService(백필) 둘 다 이 메서드를 호출.
 * [사이드 임팩트] HIGH-2 fix: @Transactional 제거 — HTTP 호출 중 DB 커넥션 미점유(커넥션 풀 고갈 방지).
 *               findById()와 casUpdateContent()는 각각 Spring Data JPA의 개별 단기 트랜잭션으로 처리.
 *               HIGH-3 fix: casUpdateContent() CAS(WHERE content_fetched_at IS NULL)로 TOCTOU 제거.
 *               동시 호출이 있어도 DB 수준 원자 UPDATE 중 1개만 성공, 나머지는 0 반환 → 중복 DART 호출 최소화.
 *               (단, 두 스레드 모두 fast-path를 통과하면 HTTP 호출은 두 번 발생 가능 — CAS가 저장 중복만 방지)
 * [수정 시 고려사항] contentMaxChars 초과 시 서로게이트 페어 경계 보호(MEDIUM-2 fix) — Unicode BMP 외 문자 보호.
 *                  Stage 3 RAG 활성화 후 content_text 갱신이 임베딩 재생성을 트리거해야 하면 이벤트 추가 필요.
 *                  텍스트 품질 이슈 발생 시 DartDocumentParser 정규식 확장(별도 수정, 이 클래스 무관).
 */
@Service
public class DisclosureContentService {

    private static final Logger log = LoggerFactory.getLogger(DisclosureContentService.class);

    private final DisclosureRepository disclosureRepository;
    private final DartDocumentClient dartDocumentClient;
    private final DartDocumentParser dartDocumentParser;
    private final DartApiProperties props;

    public DisclosureContentService(DisclosureRepository disclosureRepository,
                                    DartDocumentClient dartDocumentClient,
                                    DartDocumentParser dartDocumentParser,
                                    DartApiProperties props) {
        this.disclosureRepository = disclosureRepository;
        this.dartDocumentClient = dartDocumentClient;
        this.dartDocumentParser = dartDocumentParser;
        this.props = props;
    }

    /**
     * 공시 ID의 본문을 fetch해 content_text / content_fetched_at을 갱신한다.
     *
     * <p>멱등: casUpdateContent() CAS 조건(content_fetched_at IS NULL)으로 DB 수준 보장.
     * <p>영구 실패: {@link DartApiException} → content_fetched_at 기록(text=null). 재시도 방지.
     * <p>일시 실패: {@link RestClientException} → 아무 것도 갱신하지 않음(재시도 허용).
     *
     * @param disclosureId 갱신 대상 공시 PK
     */
    public void fetchAndSave(Long disclosureId) {
        // Spring Data JPA findById()는 자체 @Transactional(readOnly=true) — 커넥션 즉시 반환
        Disclosure disclosure = disclosureRepository.findById(disclosureId).orElse(null);
        if (disclosure == null) {
            log.warn("DisclosureContentService: disclosure not found id={}", disclosureId);
            return;
        }

        // 빠른 경로: 이미 fetch 완료(성공·영구실패 모두 포함) — HTTP 호출 없이 조기 반환
        if (disclosure.getContentFetchedAt() != null) {
            log.debug("DisclosureContentService: already fetched id={}", disclosureId);
            return;
        }

        String rceptNo = disclosure.getRceptNo();
        try {
            // HTTP 호출 — 트랜잭션 없음(커넥션 점유 없음), @Retryable은 cross-bean 경유로 정상 동작
            byte[] rawBytes = dartDocumentClient.fetchDocumentBytes(rceptNo);
            String text = dartDocumentParser.extractText(rawBytes);

            // MEDIUM-2 fix: 서로게이트 페어 경계 보호 — High Surrogate 위치에서 절단 방지
            if (text.length() > props.contentMaxChars()) {
                int limit = props.contentMaxChars();
                if (limit < text.length() && Character.isHighSurrogate(text.charAt(limit - 1))) {
                    limit--;
                }
                log.debug("DisclosureContentService: truncating content rceptNo={} len={} → {}",
                        rceptNo, text.length(), limit);
                text = text.substring(0, limit);
            }

            // HIGH-3 fix: CAS UPDATE — content_fetched_at IS NULL인 경우에만 갱신
            String finalText = text.isEmpty() ? null : text;
            int updated = disclosureRepository.casUpdateContent(disclosureId, finalText, OffsetDateTime.now());
            if (updated > 0) {
                log.info("DisclosureContentService: saved content rceptNo={} chars={}",
                        rceptNo, finalText != null ? finalText.length() : 0);
            } else {
                log.debug("DisclosureContentService: concurrent thread already saved id={}", disclosureId);
            }

        } catch (DartApiException e) {
            // 영구 실패 — CAS로 content_fetched_at 기록(text=null), 무한 재시도 방지
            log.warn("DisclosureContentService: permanent fetch failure rceptNo={} reason={}", rceptNo, e.getMessage());
            disclosureRepository.casUpdateContent(disclosureId, null, OffsetDateTime.now());

        } catch (RestClientException e) {
            // 일시 실패 — 갱신 없이 return. content_fetched_at = null 유지 → 다음 백필에서 재시도
            log.warn("DisclosureContentService: transient fetch failure rceptNo={} reason={}", rceptNo, e.getMessage());
        }
    }
}
