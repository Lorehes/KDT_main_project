# [목적] Spring Boot 멀티스테이지 빌드 — builder(JDK21)에서 컴파일, runtime(JRE21-alpine)에 JAR만 복사.
# [이유] 최종 이미지에 JDK·Gradle·소스 미포함 → 이미지 크기 축소 + 공격 표면 감소.
#   build.gradle/gradle/ 먼저 복사 후 dependencies 다운로드 → src 변경 시 deps 레이어 재사용.
# [사이드 임팩트] -x test 플래그로 테스트 생략 — CI jobs.test 단계에서 별도 실행.
# [수정 시 고려사항] Spring Boot 버전 업그레이드 시 JRE 버전(eclipse-temurin 태그) 함께 갱신.
#   다중 모듈 프로젝트 전환 시 COPY 경로와 bootJar 태스크 대상 갱신 필요.
#   JVM 메모리 옵션(-Xmx)은 EC2 인스턴스 사양에 맞게 조정 (현재 기본값 사용).

# ---- Stage 1: builder ----
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Gradle wrapper + 빌드 스크립트 먼저 복사 → 의존성 레이어 캐시
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

# 소스 복사 후 JAR 빌드 (-x test: CI 단계에서 별도 실행)
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# 비루트 유저 생성 — 컨테이너 탈출 시 권한 최소화
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /workspace/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
