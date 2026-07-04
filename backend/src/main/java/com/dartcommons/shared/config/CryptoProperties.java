package com.dartcommons.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] AES-256-GCM 암호화 키 설정 — avg_buy_price/quantity/phone_number 암호화용 키를 환경변수로 주입.
 * [이유] AES_KEY 하드코딩 금지(CLAUDE.md §7). @ConfigurationPropertiesScan 자동 발견.
 *       키는 base64 인코딩된 32바이트(256비트) 문자열. AesGcmEncryptor가 디코딩 후 SecretKey로 변환.
 * [사이드 임팩트] AES_KEY 미설정 시 @NotBlank → 부팅 즉시 실패. 키 변경 시 기존 암호화 데이터 복호화 불가.
 * [수정 시 고려사항] 프로덕션은 AWS KMS/Vault 등 외부 KMS로 키를 분리 관리(통합기획서 §11.1).
 *                  키 교체 시 기존 암호화 컬럼 재암호화 배치 필요.
 */
@ConfigurationProperties("dartcommons.crypto")
@Validated
public record CryptoProperties(
        @NotBlank String aesKey
) {
}
