#!/usr/bin/env bash
# 개발 서버에서 실행: 새 jar(app.jar.new)로 앱을 교체한다
# 순서: 파일 확인 → DB·jar 백업 → 앱 중지 → jar 교체 → 앱 시작 → health 확인
# health가 실패하면 이전 jar로 되돌리고 실패로 끝낸다
# 사용: cd ~/kt-backend && bash deploy.sh [기대하는 sha256]
set -euo pipefail

cd "$(dirname "$0")"

NEW_JAR=app.jar.new
EXPECTED_SHA="${1:-}"
HEALTH_URL=http://localhost:8080/actuator/health
HEALTH_TIMEOUT_SECONDS=150
KEEP_BACKUPS=5
TS=$(date -u +%Y%m%d-%H%M%S)

log() { echo "[deploy] $*"; }

# 요청 하나는 최대 5초, 전체는 실제로 흐른 시간으로 제한한다
# (응답 없이 멈춘 요청 때문에 복구가 늦어지지 않게)
wait_for_health() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 5
  done
  return 1
}

if [[ ! -s "$NEW_JAR" ]]; then
  log "새 jar($NEW_JAR)가 없습니다"
  exit 1
fi

# 전송 중 손상 확인
if [[ -n "$EXPECTED_SHA" ]]; then
  ACTUAL_SHA=$(sha256sum "$NEW_JAR" | cut -d' ' -f1)
  if [[ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
    log "jar 해시가 다릅니다 (기대 $EXPECTED_SHA, 실제 $ACTUAL_SHA)"
    exit 1
  fi
fi

mkdir -p backup
chmod 700 backup

# migration은 되돌릴 수 없으므로 배포마다 DB를 먼저 백업한다
log "DB 백업"
sudo docker compose exec -T mysql sh -c \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines kt_scholarship 2>/dev/null' \
  > "backup/kt_scholarship-$TS.sql"
chmod 600 "backup/kt_scholarship-$TS.sql"
cp app.jar "backup/app-$TS.jar"

# 실행 중인 jar를 덮어쓰면 앱이 깨질 수 있어서 멈춘 뒤 교체한다
# 컨테이너는 app.jar 파일을 연결해 쓰므로 mv가 아니라 cp로 같은 파일에 덮어쓴다
log "앱 중지 → jar 교체 → 시작"
sudo docker compose stop app

# 앱을 멈춘 뒤에는 교체·시작·health 중 무엇이 실패해도 이전 jar로 복구한다
# (if 조건 안의 명령은 set -e로 바로 종료되지 않는다)
if cp "$NEW_JAR" app.jar \
  && sudo docker compose up -d app \
  && wait_for_health; then
  rm -f "$NEW_JAR"
  log "배포 성공: $(curl -fsS --max-time 5 "$HEALTH_URL")"
else
  log "배포 실패(교체·시작·health 중 하나): 이전 jar로 복구합니다"
  sudo docker compose logs app --tail 80 || true
  sudo docker compose stop app || true
  if cp "backup/app-$TS.jar" app.jar \
    && sudo docker compose up -d app \
    && wait_for_health; then
    log "이전 jar로 복구 완료. DB가 바뀌었다면 backup/kt_scholarship-$TS.sql 확인 필요"
  else
    log "복구도 실패했습니다. 서버에서 직접 확인이 필요합니다"
  fi
  exit 1
fi

# 오래된 백업 정리 (최근 KEEP_BACKUPS개만 유지)
ls -1t backup/app-*.jar 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f
ls -1t backup/kt_scholarship-*.sql 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f
