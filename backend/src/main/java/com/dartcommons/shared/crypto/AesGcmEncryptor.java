package com.dartcommons.shared.crypto;

import com.dartcommons.shared.config.CryptoProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/*
 * [목적] AES-256-GCM 대칭 암복호화 — avg_buy_price_enc / quantity_enc / phone_number_enc BYTEA 컬럼 처리.
 *       저장 형식: IV(12 bytes) ‖ ciphertext+GCM_tag(16 bytes) — 단일 byte[]로 결합.
 * [이유] AES-256-GCM은 인증 암호화(AEAD)로 무결성·기밀성 동시 보장 — NIST 권장 모드(SP 800-38D).
 *       GCM tag가 복호화 시 자동 검증되므로 별도 MAC 불필요. Java GCM이 ciphertext 뒤에 tag 자동 부가.
 *       BYTEA 컬럼에 IV+ciphertext+tag 결합 저장 — IV 재사용 없이 암호화마다 새 IV 생성(보안 필수).
 * [사이드 임팩트] 같은 평문도 암호화할 때마다 다른 결과 → DB 정렬/동등 비교 불가.
 *               손익 계산은 복호화 후 애플리케이션 계층에서만 수행(db_schema §3.3).
 *               AES_KEY 변경 시 기존 암호화 데이터 복호화 불가(재암호화 배치 필요).
 * [수정 시 고려사항] 키는 CryptoProperties(AES_KEY env)에서 주입 — 하드코딩 절대 금지(CLAUDE.md §7).
 *                  프로덕션에서는 AWS KMS/HashiCorp Vault로 키를 관리하고 여기서는 DEK만 사용 권장.
 *                  null 입력(선택 필드)은 null 반환 — 호출자가 null 체크 후 컬럼에 저장.
 */
@Component
public class AesGcmEncryptor {

    private static final String ALGORITHM   = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH   = 12;   // GCM 권장 96bit IV
    private static final int    GCM_TAG_BIT = 128;  // 인증 태그 128bit

    private final SecretKey secretKey;

    public AesGcmEncryptor(CryptoProperties props) {
        byte[] keyBytes = Base64.getDecoder().decode(props.aesKey());
        if (keyBytes.length != 32) {
            throw new IllegalStateException("AES_KEY must be base64-encoded 32 bytes (256 bits)");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 평문 문자열을 암호화해 IV‖ciphertext+tag 형태의 byte[]로 반환.
     * null 입력이면 null 반환(선택 필드 처리 용이).
     */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BIT, iv));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[IV_LENGTH + ciphertextWithTag.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH);
            System.arraycopy(ciphertextWithTag, 0, result, IV_LENGTH, ciphertextWithTag.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM encryption failed", e);
        }
    }

    /**
     * IV‖ciphertext+tag 형태의 byte[]를 복호화해 평문 문자열로 반환.
     * null 입력이면 null 반환.
     */
    public String decrypt(byte[] data) {
        if (data == null) return null;
        try {
            byte[] iv             = Arrays.copyOfRange(data, 0, IV_LENGTH);
            byte[] ciphertextWithTag = Arrays.copyOfRange(data, IV_LENGTH, data.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BIT, iv));
            byte[] plainBytes = cipher.doFinal(ciphertextWithTag);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM decryption failed", e);
        }
    }

    /** 암복호화 실패 시 사용하는 unchecked 예외. */
    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
