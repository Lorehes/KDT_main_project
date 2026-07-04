package com.dartcommons.shared.config;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] system_configs(V11) 매핑 — 시스템 잡 상태(lastPolledDate 등)의 key-value 영속화.
 * [이유] @Scheduled 잡의 상태가 인메모리면 재기동 시 누락 — DB 영속화로 회복.
 * [사이드 임팩트] 다른 도메인의 잡 상태도 같은 표 공유 — config_key 네이밍 컨벤션(`<domain>.<key>`) 권수.
 * [수정 시 고려사항] 본 엔티티는 shared/config에 위치 — 모든 도메인 read/write 허용(마스터 데이터 예외와 유사 결).
 *                  값 길이 500 충분치 못하면 V{n} 새 마이그레이션으로 확장.
 */
@Entity
@Table(name = "system_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SystemConfig {

    @Id
    @Column(name = "config_key", length = 100)
    private String key;

    @Column(name = "config_value", nullable = false, length = 500)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static SystemConfig of(String key, String value) {
        return SystemConfig.builder()
                .key(key)
                .value(value)
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    public void update(String value) {
        this.value = value;
        this.updatedAt = OffsetDateTime.now();
    }
}
