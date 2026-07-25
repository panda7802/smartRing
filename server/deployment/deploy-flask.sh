#!/usr/bin/env bash
set -Eeuo pipefail

readonly APP_ROOT="/opt/smartRing"
readonly SERVER_DIRECTORY="${APP_ROOT}/server"
readonly VENV_DIRECTORY="${APP_ROOT}/venv"
readonly BACKUP_ROOT="${APP_ROOT}/backups"
readonly RELEASE_ROOT="${APP_ROOT}/releases"
readonly DATABASE_PATH="/var/lib/smartRing/smartring.db"
readonly SERVICE_TARGET="/etc/systemd/system/smartring.service"
readonly STAGED_ARCHIVE="/tmp/smartring-flask.tar.gz"
readonly STAGED_SERVICE="/tmp/smartring.service"
readonly DEPLOYMENT_STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
readonly STAGED_SERVER="${RELEASE_ROOT}/server-${DEPLOYMENT_STAMP}"
readonly BACKUP_DIRECTORY="${BACKUP_ROOT}/flask-${DEPLOYMENT_STAMP}"
readonly DATABASE_BACKUP="${BACKUP_DIRECTORY}/smartring.db"

deployment_started=0

database_counts() {
    "${VENV_DIRECTORY}/bin/python" - "${DATABASE_PATH}" <<'PY'
import json
import sqlite3
import sys

connection = sqlite3.connect(sys.argv[1])
try:
    counts = {
        "users": connection.execute("SELECT COUNT(*) FROM users").fetchone()[0],
        "admins": connection.execute("SELECT COUNT(*) FROM admin_users").fetchone()[0],
        "tables": [
            row[0]
            for row in connection.execute(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                ORDER BY name
                """
            )
        ],
    }
finally:
    connection.close()
print(json.dumps(counts, sort_keys=True))
PY
}

copy_database() {
    "${VENV_DIRECTORY}/bin/python" - "$1" "$2" <<'PY'
import sqlite3
import sys

source = sqlite3.connect(sys.argv[1])
destination = sqlite3.connect(sys.argv[2])
try:
    source.backup(destination)
    destination.execute("PRAGMA wal_checkpoint(TRUNCATE)")
finally:
    destination.close()
    source.close()
PY
}

rollback() {
    trap - ERR
    if [[ "${deployment_started}" -eq 1 ]]; then
        systemctl stop smartring.service || true
        if [[ -d "${SERVER_DIRECTORY}" ]]; then
            mv "${SERVER_DIRECTORY}" "${BACKUP_DIRECTORY}/failed-server"
        fi
        if [[ -d "${BACKUP_DIRECTORY}/server" ]]; then
            mv "${BACKUP_DIRECTORY}/server" "${SERVER_DIRECTORY}"
        fi
        if [[ -f "${BACKUP_DIRECTORY}/smartring.service" ]]; then
            cp -a "${BACKUP_DIRECTORY}/smartring.service" "${SERVICE_TARGET}"
        fi
        if [[ -f "${DATABASE_BACKUP}" ]]; then
            copy_database "${DATABASE_BACKUP}" "${DATABASE_PATH}"
        fi
        systemctl daemon-reload || true
        systemctl restart smartring.service || true
    fi
}
trap rollback ERR

test -f "${STAGED_ARCHIVE}"
test -f "${STAGED_SERVICE}"
install -d -o root -g root -m 0755 "${BACKUP_ROOT}" "${RELEASE_ROOT}"
mkdir -p "${STAGED_SERVER}" "${BACKUP_DIRECTORY}"
tar -xzf "${STAGED_ARCHIVE}" -C "${STAGED_SERVER}"

if [[ ! -x "${VENV_DIRECTORY}/bin/pip" ]]; then
    if [[ -e "${VENV_DIRECTORY}" ]]; then
        mv "${VENV_DIRECTORY}" "${BACKUP_DIRECTORY}/incomplete-venv"
    fi
    python3 -m venv "${VENV_DIRECTORY}"
fi
"${VENV_DIRECTORY}/bin/pip" install --disable-pip-version-check \
    -r "${STAGED_SERVER}/requirements.txt"

(
    cd "${STAGED_SERVER}"
    "${VENV_DIRECTORY}/bin/python" -m unittest discover -s tests -v
)

printf 'database_before='
database_counts
copy_database "${DATABASE_PATH}" "${DATABASE_BACKUP}"
cp -a "${SERVICE_TARGET}" "${BACKUP_DIRECTORY}/smartring.service"

deployment_started=1
systemctl stop smartring.service
mv "${SERVER_DIRECTORY}" "${BACKUP_DIRECTORY}/server"
mv "${STAGED_SERVER}" "${SERVER_DIRECTORY}"
install -o root -g root -m 0644 "${STAGED_SERVICE}" "${SERVICE_TARGET}"
systemctl daemon-reload
systemctl restart smartring.service

for attempt in {1..20}; do
    if curl -fsS http://127.0.0.1:8001/admin/ >/dev/null; then
        break
    fi
    if [[ "${attempt}" -eq 20 ]]; then
        false
    fi
    sleep 0.5
done

systemctl is-active --quiet smartring.service
printf 'database_after='
database_counts
printf 'admin_probe=ok'
printf '\nprocesses:\n'
pgrep -a -f 'gunicorn.*wsgi:app'
trap - ERR

rm -f -- "${STAGED_ARCHIVE}" "${STAGED_SERVICE}" /tmp/deploy-flask.sh
