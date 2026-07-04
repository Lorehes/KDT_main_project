#!/bin/sh
# [목적] Lightsail 최초 배포 시 Let's Encrypt 인증서 부트스트랩(1회 실행).
#   dummy 자가서명 인증서 → nginx 기동 → 실제 인증서 발급 → nginx 무중단 리로드.
# [이유] nginx SSL server 블록은 인증서 파일이 없으면 로드에 실패해 컨테이너가 crash한다.
#   → 임시 dummy cert로 nginx를 먼저 띄운 뒤, 그 nginx가 서빙하는 ACME challenge로 실제 발급한다.
# [사이드 임팩트] CERTBOT_STAGING=1 이면 Let's Encrypt 스테이징(신뢰 안 되는 테스트 인증서)으로 발급 —
#   레이트리밋 소진 없이 리허설 가능. 실발급은 STAGING=0(기본).
# [수정 시 고려사항] 도메인/이메일은 .env(DOMAIN, CERTBOT_EMAIL)에서 읽는다.
#   서버 env 파일은 .env — docker-compose.lightsail.yml 이 env_file: .env / ${} 보간 모두 이 파일을 사용.
#   www 서브도메인 추가 시 아래 CERT_DOMAINS 에 -d www.${DOMAIN} 추가(www DNS 선행 필수).
#   재발급(도메인 변경 등)은 CERTBOT_CONF_DIR 의 해당 도메인 디렉토리 삭제 후 재실행.
set -e

COMPOSE="docker compose -f docker-compose.lightsail.yml"

# ----- .env 로드 (compose 기본 env 파일) -----
if [ ! -f ./.env ]; then
  echo "ERROR: .env 가 없습니다. 'cp .env.prod.example .env' 후 값을 채우세요." >&2
  exit 1
fi
set -a
. ./.env
set +a

DOMAIN="${DOMAIN:?ERROR: .env에 DOMAIN 미설정 (예: DOMAIN=gangwoncanvas.co.kr)}"
EMAIL="${CERTBOT_EMAIL:?ERROR: .env에 CERTBOT_EMAIL 미설정}"
CONF_DIR="${CERTBOT_CONF_DIR:-/home/ubuntu/data/certbot/conf}"
WWW_DIR="${CERTBOT_WWW_DIR:-/home/ubuntu/data/certbot/www}"
STAGING="${CERTBOT_STAGING:-0}"
RSA_KEY_SIZE=4096

# 발급 대상 도메인 목록 (www 추가 시 여기 확장)
CERT_DOMAINS="-d ${DOMAIN}"

echo "### 대상 도메인: ${DOMAIN} / 이메일: ${EMAIL} / staging=${STAGING}"

# ----- 0. bind mount 디렉토리 준비 -----
mkdir -p "${CONF_DIR}" "${WWW_DIR}"

# ----- 1. dummy 인증서 생성 (nginx 부트용) -----
LIVE_PATH="/etc/letsencrypt/live/${DOMAIN}"
echo "### 1) dummy 인증서 생성: ${LIVE_PATH}"
$COMPOSE run --rm --entrypoint "\
  sh -c 'mkdir -p ${LIVE_PATH} && \
  openssl req -x509 -nodes -newkey rsa:${RSA_KEY_SIZE} -days 1 \
    -keyout ${LIVE_PATH}/privkey.pem \
    -out ${LIVE_PATH}/fullchain.pem \
    -subj \"/CN=localhost\"'" certbot

# ----- 2. nginx 기동 (dummy cert로 SSL 블록 로드 성공) -----
echo "### 2) nginx 기동"
$COMPOSE up -d nginx

# ----- 3. dummy 인증서 삭제 (실제 발급 전 정리) -----
echo "### 3) dummy 인증서 삭제"
$COMPOSE run --rm --entrypoint "\
  rm -rf /etc/letsencrypt/live/${DOMAIN} \
    /etc/letsencrypt/archive/${DOMAIN} \
    /etc/letsencrypt/renewal/${DOMAIN}.conf" certbot

# ----- 4. 실제 인증서 발급 (webroot HTTP-01) -----
echo "### 4) Let's Encrypt 실제 인증서 발급"
STAGING_ARG=""
if [ "${STAGING}" != "0" ]; then STAGING_ARG="--staging"; fi

$COMPOSE run --rm --entrypoint "\
  certbot certonly --webroot -w /var/www/certbot \
    ${STAGING_ARG} \
    ${CERT_DOMAINS} \
    --email ${EMAIL} \
    --rsa-key-size ${RSA_KEY_SIZE} \
    --agree-tos \
    --no-eff-email \
    --force-renewal" certbot

# ----- 5. nginx 리로드 (실제 인증서 반영) -----
echo "### 5) nginx 리로드"
$COMPOSE exec nginx nginx -s reload

echo "### 완료 — https://${DOMAIN} 접속 확인. 전체 스택 기동: ${COMPOSE} up -d"
