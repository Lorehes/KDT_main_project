# [목적] Next.js 멀티스테이지 빌드 — node:22-alpine 기반, output: 'standalone' 활용.
# [이유] standalone 모드는 필요한 node_modules만 번들링 → 이미지 크기 최소화.
#   deps → builder → runtime 3단계 분리로 레이어 캐시 최적화.
#   NEXT_PUBLIC_API_URL은 빌드 타임에 번들에 하드코딩됨 → ARG로 CI에서 주입.
# [사이드 임팩트] NEXT_PUBLIC_API_URL 변경 시 이미지 재빌드 필수 (런타임 주입 불가).
#   API 도메인 변경 시 CI GitHub Actions deploy.yml의 build-args도 함께 갱신.
# [수정 시 고려사항] pnpm 버전 변경 시 base 스테이지 npm install -g pnpm@{version} 한 곳만 갱신.
#   public/ 폴더 내 대용량 파일 추가 시 이미지 크기 모니터링 필요.
#   App Router middleware 추가 시 standalone output 정합성 재확인.

# ---- Base: pnpm 사전 설치 (deps·builder 공용) ----
FROM node:22-alpine AS base
RUN npm install -g pnpm@9.15.9

# ---- Stage 1: 의존성 설치 ----
FROM base AS deps
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

# ---- Stage 2: 빌드 ----
FROM base AS builder
WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_API_URL: 빌드 시 CSP connect-src 및 API 호출 origin으로 번들에 삽입됨.
# CI deploy.yml의 build-args에서 GitHub Secrets.NEXT_PUBLIC_API_URL로 주입.
ARG NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL

RUN pnpm build

# ---- Stage 3: 런타임 ----
FROM node:22-alpine AS runtime
WORKDIR /app

# 비루트 유저 생성 — 컨테이너 탈출 시 권한 최소화
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# standalone 빌드 결과물만 복사 (node_modules 포함 번들)
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

RUN chown -R appuser:appgroup /app
USER appuser

ENV NODE_ENV=production
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"
EXPOSE 3000

CMD ["node", "server.js"]
