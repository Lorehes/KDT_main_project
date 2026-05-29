/**
 * 인프라 모듈 — 외부 연동 격리.
 *
 * <p>WebClient 기반 외부 클라이언트(DART/KRX/공공 API), LLM provider 어댑터(LangChain4j: Ollama/Cloud),
 * Chroma 클라이언트, 알림 채널(카카오/텔레그램/메일). 모든 호출에 타임아웃·지수 백오프 설정,
 * 키는 환경변수로만 주입(CLAUDE.md 4장).
 *
 * <p>이 모듈은 도메인을 import 하지 않는다(도메인이 인터페이스로 의존).
 * 표준 하위 패키지/클라이언트는 해당 기능 구현 시 생성(빈 폴더 금지).
 */
package com.dartcommons.infrastructure;
