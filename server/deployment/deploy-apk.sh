#!/usr/bin/env bash
set -Eeuo pipefail

readonly STAGED_APK="/tmp/app-debug.apk"
readonly STAGED_NGINX="/tmp/doudou-game-box.conf"
readonly APK_DIRECTORY="/opt/smartRing/downloads"
readonly APK_TARGET="${APK_DIRECTORY}/sr.apk"
readonly NGINX_TARGET="/etc/nginx/sites-available/doudou-game-box"
readonly BACKUP_DIRECTORY="/opt/smartRing/backups"
readonly DEPLOYMENT_STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
readonly NGINX_BACKUP="${BACKUP_DIRECTORY}/doudou-game-box.${DEPLOYMENT_STAMP}.conf"
readonly APK_BACKUP="${BACKUP_DIRECTORY}/sr.${DEPLOYMENT_STAMP}.apk"

test -f "${STAGED_APK}"
test -f "${STAGED_NGINX}"
install -d -o root -g root -m 0755 "${APK_DIRECTORY}" "${BACKUP_DIRECTORY}"
cp -a "${NGINX_TARGET}" "${NGINX_BACKUP}"

had_apk=0
if [[ -f "${APK_TARGET}" ]]; then
    cp -a "${APK_TARGET}" "${APK_BACKUP}"
    had_apk=1
fi

rollback() {
    cp -a "${NGINX_BACKUP}" "${NGINX_TARGET}"
    if [[ "${had_apk}" -eq 1 ]]; then
        cp -a "${APK_BACKUP}" "${APK_TARGET}"
    else
        rm -f -- "${APK_TARGET}"
    fi
    nginx -t && systemctl reload nginx || true
}
trap rollback ERR

install -o root -g root -m 0644 "${STAGED_APK}" "${APK_TARGET}"
install -o root -g root -m 0644 "${STAGED_NGINX}" "${NGINX_TARGET}"
nginx -t
systemctl reload nginx
trap - ERR

sha256sum "${APK_TARGET}"
stat -c 'size=%s mode=%a owner=%U:%G path=%n' "${APK_TARGET}"
rm -f -- "${STAGED_APK}" "${STAGED_NGINX}" /tmp/deploy-apk.sh
