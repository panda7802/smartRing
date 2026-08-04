#!/usr/bin/env python3
"""smartRing Flask application and SQLite-backed business service."""

from __future__ import annotations

import calendar
import hashlib
import hmac
import html
import json
import logging
import os
import secrets
import sqlite3
import sys
import time
import uuid
from argparse import ArgumentParser
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from getpass import getpass
from pathlib import Path
from typing import Any, Iterable

from flask import Flask, Response, jsonify, make_response, request, send_file


LOGGER = logging.getLogger("smartring")
MAX_BODY_BYTES = 16 * 1024
TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60
ADMIN_TOKEN_TTL_SECONDS = 12 * 60 * 60
ADMIN_COOKIE_NAME = "smartRingAdmin"
PUBLIC_PREFIX = "/smartRing"
MAX_TASBEEH_COUNT = 9_007_199_254_740_991
MAX_BLESSING_NICKNAME_LENGTH = 40
MAX_BLESSING_MESSAGE_LENGTH = 280
MAX_PACKAGE_NAME_LENGTH = 255
MAX_BLESSING_EVENT_KEY_LENGTH = 64
CHINA_TIMEZONE = timezone(timedelta(hours=8))


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


class WebResponse:
    def __init__(
        self,
        status: int,
        body: str,
        headers: list[tuple[str, str]] | None = None,
    ) -> None:
        self.status = status
        self.body = body.encode("utf-8")
        self.headers = headers or []


def utc_iso(epoch_seconds: int | None = None) -> str:
    value = time.time() if epoch_seconds is None else epoch_seconds
    return datetime.fromtimestamp(value, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def password_md5(password: str) -> str:
    # MD5 is retained here because it is an explicit compatibility requirement.
    return hashlib.md5(password.encode("utf-8")).hexdigest()


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


class SmartRingService:
    def __init__(self, database_path: str | os.PathLike[str]) -> None:
        self.database_path = str(database_path)
        Path(self.database_path).parent.mkdir(parents=True, exist_ok=True)
        self._initialize_database()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    @contextmanager
    def _connection(self) -> Iterable[sqlite3.Connection]:
        connection = self._connect()
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def _initialize_database(self) -> None:
        with self._connection() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                DROP TABLE IF EXISTS tasbeeh_records;

                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE COLLATE BINARY,
                    passwd_md5 TEXT NOT NULL CHECK(length(passwd_md5) = 32),
                    created_at INTEGER NOT NULL,
                    tasbeeh_reset_pending INTEGER NOT NULL DEFAULT 1
                        CHECK(tasbeeh_reset_pending IN (0, 1))
                );

                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    token_hash TEXT NOT NULL UNIQUE CHECK(length(token_hash) = 64),
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    revoked_at INTEGER
                );

                CREATE INDEX IF NOT EXISTS idx_sessions_user_id
                    ON sessions(user_id);
                CREATE INDEX IF NOT EXISTS idx_sessions_token_active
                    ON sessions(token_hash, expires_at, revoked_at);

                CREATE TABLE IF NOT EXISTS admin_users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE COLLATE BINARY,
                    passwd_md5 TEXT NOT NULL CHECK(length(passwd_md5) = 32),
                    created_at INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS admin_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    admin_user_id INTEGER NOT NULL
                        REFERENCES admin_users(id) ON DELETE CASCADE,
                    token_hash TEXT NOT NULL UNIQUE CHECK(length(token_hash) = 64),
                    csrf_hash TEXT NOT NULL CHECK(length(csrf_hash) = 64),
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    revoked_at INTEGER
                );

                CREATE INDEX IF NOT EXISTS idx_admin_sessions_token_active
                    ON admin_sessions(token_hash, expires_at, revoked_at);

                CREATE TABLE IF NOT EXISTS blessing_tags (
                    id TEXT PRIMARY KEY,
                    owner_user_id INTEGER NOT NULL
                        REFERENCES users(id) ON DELETE CASCADE,
                    nickname TEXT NOT NULL,
                    message TEXT NOT NULL,
                    package_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_blessing_tags_owner
                    ON blessing_tags(owner_user_id, created_at DESC);

                CREATE TABLE IF NOT EXISTS blessing_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_key TEXT NOT NULL UNIQUE,
                    blessing_id TEXT NOT NULL
                        REFERENCES blessing_tags(id) ON DELETE CASCADE,
                    recipient_user_id INTEGER NOT NULL
                        REFERENCES users(id) ON DELETE CASCADE,
                    created_at INTEGER NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_blessing_events_blessing
                    ON blessing_events(blessing_id, created_at DESC);
                CREATE INDEX IF NOT EXISTS idx_blessing_events_recipient
                    ON blessing_events(recipient_user_id, created_at DESC);
                """
            )
            user_columns = {
                str(row["name"])
                for row in connection.execute("PRAGMA table_info(users)")
            }
            if "tasbeeh_reset_pending" not in user_columns:
                connection.execute(
                    """
                    ALTER TABLE users
                    ADD COLUMN tasbeeh_reset_pending INTEGER NOT NULL DEFAULT 1
                        CHECK(tasbeeh_reset_pending IN (0, 1))
                    """
                )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS tasbeeh_daily_counts (
                    user_id INTEGER NOT NULL
                        REFERENCES users(id) ON DELETE CASCADE,
                    count_date TEXT NOT NULL CHECK(length(count_date) = 8),
                    count INTEGER NOT NULL CHECK(count >= 0),
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(user_id, count_date)
                )
                """
            )

    @staticmethod
    def _json_body() -> dict[str, Any]:
        content_type = (request.mimetype or "").lower()
        if content_type != "application/json":
            raise ApiError(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type 必须是 application/json")

        length = request.content_length or 0
        if length > MAX_BODY_BYTES:
            raise ApiError(413, "BODY_TOO_LARGE", "JSON 请求体不能超过 16 KiB")

        raw = request.get_data(cache=True)
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(400, "INVALID_JSON", "请求体不是有效的 UTF-8 JSON") from exc
        if not isinstance(value, dict):
            raise ApiError(400, "INVALID_JSON", "JSON 请求体必须是对象")
        return value

    @staticmethod
    def _credentials(body: dict[str, Any]) -> tuple[str, str]:
        name = body.get("name")
        password = body.get("passwd")
        if not isinstance(name, str) or not name or len(name) > 64 or name != name.strip():
            raise ApiError(400, "INVALID_NAME", "name 必须是 1 到 64 个字符且首尾不能有空格")
        if not isinstance(password, str) or not password or len(password) > 128:
            raise ApiError(400, "INVALID_PASSWORD", "passwd 必须是 1 到 128 个字符")
        return name, password

    @staticmethod
    def _tasbeeh_count(body: dict[str, Any]) -> int:
        value = body.get("count")
        if isinstance(value, bool):
            raise ApiError(400, "INVALID_COUNT", "count 必须是非负整数或整数字符串")
        if isinstance(value, int):
            count = value
        elif isinstance(value, str) and value and value.isascii() and value.isdigit():
            count = int(value)
        else:
            raise ApiError(400, "INVALID_COUNT", "count 必须是非负整数或整数字符串")
        if count < 0 or count > MAX_TASBEEH_COUNT:
            raise ApiError(
                400,
                "INVALID_COUNT",
                f"count 必须在 0 到 {MAX_TASBEEH_COUNT} 之间",
            )
        return count

    @staticmethod
    def _required_text(
        body: dict[str, Any],
        field: str,
        maximum_length: int,
        error_code: str,
    ) -> str:
        value = body.get(field)
        if (
            not isinstance(value, str)
            or not value.strip()
            or value != value.strip()
            or len(value) > maximum_length
        ):
            raise ApiError(
                400,
                error_code,
                f"{field} 必须是 1 到 {maximum_length} 个字符且首尾不能有空格",
            )
        return value

    @staticmethod
    def _china_date() -> str:
        return datetime.now(CHINA_TIMEZONE).strftime("%Y%m%d")

    def set_admin(self, name: str, password: str) -> None:
        if not name or len(name) > 64 or name != name.strip():
            raise ValueError("管理员用户名必须是 1 到 64 个字符且首尾不能有空格")
        if not password or len(password) > 128:
            raise ValueError("管理员密码必须是 1 到 128 个字符")
        now = int(time.time())
        with self._connection() as connection:
            connection.execute(
                """
                INSERT INTO admin_users(name, passwd_md5, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET passwd_md5 = excluded.passwd_md5
                """,
                (name, password_md5(password), now),
            )
            admin = connection.execute(
                "SELECT id FROM admin_users WHERE name = ?", (name,)
            ).fetchone()
            connection.execute(
                """
                UPDATE admin_sessions SET revoked_at = ?
                WHERE admin_user_id = ? AND revoked_at IS NULL
                """,
                (now, int(admin["id"])),
            )

    @staticmethod
    def _form_body() -> dict[str, str]:
        content_type = (request.mimetype or "").lower()
        if content_type != "application/x-www-form-urlencoded":
            raise ApiError(415, "UNSUPPORTED_MEDIA_TYPE", "表单格式不正确")
        length = request.content_length or 0
        if length > MAX_BODY_BYTES:
            raise ApiError(413, "BODY_TOO_LARGE", "表单不能超过 16 KiB")
        return request.form.to_dict(flat=True)

    @staticmethod
    def _admin_cookie_token() -> str | None:
        return request.cookies.get(ADMIN_COOKIE_NAME)

    def _admin_session(self) -> sqlite3.Row | None:
        token = self._admin_cookie_token()
        if not token:
            return None
        with self._connection() as connection:
            return connection.execute(
                """
                SELECT s.id, s.admin_user_id, s.csrf_hash, a.name
                FROM admin_sessions AS s
                JOIN admin_users AS a ON a.id = s.admin_user_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ?
                """,
                (token_hash(token), int(time.time())),
            ).fetchone()

    @staticmethod
    def _page(title: str, content: str) -> str:
        return (
            "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            f"<title>{html.escape(title)} · smartRing</title>"
            "<style>"
            ":root{color-scheme:light;--ink:#17211b;--muted:#657069;--line:#dce5df;"
            "--brand:#176c4b;--brand2:#0e5037;--paper:#fff;--bg:#f2f6f3;--danger:#b42318}"
            "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);"
            "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif}"
            ".top{background:linear-gradient(135deg,var(--brand2),var(--brand));color:#fff;padding:24px 0}"
            ".wrap{width:min(1120px,calc(100% - 32px));margin:auto}.brand{font-size:24px;font-weight:750}"
            ".sub{margin-top:5px;color:#d8eee4;font-size:14px}.card{background:var(--paper);border:1px solid var(--line);"
            "border-radius:16px;box-shadow:0 10px 32px rgba(20,60,40,.07)}"
            ".login{width:min(420px,calc(100% - 32px));margin:9vh auto;padding:30px}"
            "h1{font-size:24px;margin:0 0 8px}.hint{color:var(--muted);margin:0 0 24px;line-height:1.6}"
            "label{display:block;font-weight:650;margin:16px 0 7px}input{width:100%;border:1px solid #bcc9c1;"
            "border-radius:10px;padding:12px 13px;font:inherit;outline:none}input:focus{border-color:var(--brand);"
            "box-shadow:0 0 0 3px rgba(23,108,75,.12)}button{border:0;border-radius:10px;background:var(--brand);"
            "color:#fff;padding:11px 18px;font:inherit;font-weight:700;cursor:pointer}button:hover{background:var(--brand2)}"
            ".login button{width:100%;margin-top:22px}.error{background:#fff0ef;color:var(--danger);padding:11px 13px;"
            "border-radius:9px;margin:12px 0}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px}"
            ".toolbar form{margin:0}.ghost{background:rgba(255,255,255,.14);border:1px solid rgba(255,255,255,.35)}"
            ".toolbar-actions{display:flex;align-items:center;gap:10px}.ghost-link{color:#fff;text-decoration:none;"
            "border:1px solid rgba(255,255,255,.35);border-radius:10px;padding:10px 16px;background:rgba(255,255,255,.14)}"
            ".main{padding:28px 0 48px}.stats{display:grid;grid-template-columns:minmax(0,1fr);gap:16px;margin-bottom:20px}"
            ".stat{padding:20px}.stat strong{display:block;font-size:30px;color:var(--brand);margin-top:6px}.stat span{color:var(--muted)}"
            ".table-card{overflow:hidden}.table-head{padding:20px 22px;border-bottom:1px solid var(--line)}"
            ".table-head h1{font-size:19px}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:15px 22px;"
            "border-bottom:1px solid #edf1ee}th{font-size:13px;color:var(--muted);background:#fafcfb}tbody tr:last-child td{border:0}"
            ".num{font-weight:750;color:var(--brand)}.empty{text-align:center;color:var(--muted);padding:40px}"
            ".action-link{color:var(--brand);font-weight:700;text-decoration:none}.action-link:hover{text-decoration:underline}"
            ".calendar-nav{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:20px 22px;"
            "border-bottom:1px solid var(--line)}.calendar-nav h1{font-size:20px;margin:0;text-align:center}"
            ".month-link{color:var(--brand);font-weight:700;text-decoration:none;border:1px solid var(--line);"
            "border-radius:9px;padding:9px 12px;background:#fff}.calendar-table{table-layout:fixed}"
            ".calendar-table th{text-align:center;padding:12px 8px}.calendar-table td{height:118px;vertical-align:top;"
            "padding:10px;border-right:1px solid #edf1ee;white-space:normal}.calendar-table tr td:last-child{border-right:0}"
            ".calendar-table tbody tr:last-child td{border-bottom:0}.calendar-day{font-weight:700;color:var(--muted)}"
            ".calendar-count{font-size:24px;font-weight:800;color:var(--brand);margin-top:14px;word-break:break-all}"
            ".calendar-unit{font-size:12px;color:var(--muted);margin-top:3px}.outside{background:#f8faf9}"
            ".today{box-shadow:inset 0 0 0 2px var(--brand);background:#f3fbf7}"
            "@media(max-width:700px){.stats{grid-template-columns:1fr}.table-scroll{overflow-x:auto}"
            "th,td{white-space:nowrap;padding:13px 16px}.toolbar{align-items:flex-start}.toolbar-actions{flex-direction:column;"
            "align-items:stretch}.calendar-table{min-width:760px}.calendar-nav{min-width:760px}}"
            "</style></head><body>" + content + "</body></html>"
        )

    def _admin_login_page(self, error: str | None = None) -> WebResponse:
        error_html = f"<div class='error'>{html.escape(error)}</div>" if error else ""
        content = (
            "<main class='login card'><h1>smartRing 管理后台</h1>"
            "<p class='hint'>登录后可查看全部注册用户信息。</p>"
            + error_html
            + f"<form method='post' action='{PUBLIC_PREFIX}/admin/login'>"
            "<label for='name'>用户名</label><input id='name' name='name' autocomplete='username' required autofocus>"
            "<label for='passwd'>密码</label><input id='passwd' name='passwd' type='password' autocomplete='current-password' required>"
            "<button type='submit'>登录</button></form></main>"
        )
        return WebResponse(200 if error is None else 401, self._page("后台登录", content))

    @staticmethod
    def _china_time(epoch_seconds: int) -> str:
        china_tz = timezone(timedelta(hours=8))
        return datetime.fromtimestamp(epoch_seconds, china_tz).strftime("%Y-%m-%d %H:%M:%S")

    def _admin_dashboard(self, session: sqlite3.Row, csrf_token: str) -> WebResponse:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT id, name, created_at
                FROM users
                ORDER BY created_at DESC, id DESC
                """
            ).fetchall()
        total_users = len(rows)
        if rows:
            body_rows = "".join(
                "<tr>"
                f"<td class='num'>{int(row['id'])}</td>"
                f"<td>{html.escape(str(row['name']))}</td>"
                f"<td>{self._china_time(int(row['created_at']))}</td>"
                f"<td><a class='action-link' href='{PUBLIC_PREFIX}/admin/user?id={int(row['id'])}'>"
                "查看赞念日历</a></td>"
                "</tr>"
                for row in rows
            )
        else:
            body_rows = "<tr><td class='empty' colspan='4'>暂无注册用户</td></tr>"
        content = (
            "<header class='top'><div class='wrap toolbar'><div><div class='brand'>smartRing 管理后台</div>"
            f"<div class='sub'>管理员：{html.escape(str(session['name']))}</div></div>"
            f"<form method='post' action='{PUBLIC_PREFIX}/admin/logout'>"
            f"<input type='hidden' name='csrf' value='{html.escape(csrf_token)}'>"
            "<button class='ghost' type='submit'>退出登录</button></form></div></header>"
            "<main class='wrap main'><section class='stats'>"
            f"<div class='card stat'><span>注册用户</span><strong>{total_users}</strong></div>"
            "</section><section class='card table-card'><div class='table-head'><h1>注册用户信息</h1></div>"
            "<div class='table-scroll'><table><thead><tr><th>用户 ID</th><th>用户名</th>"
            "<th>注册时间</th><th>赞念记录</th></tr></thead><tbody>"
            + body_rows
            + "</tbody></table></div></section></main>"
        )
        return WebResponse(200, self._page("用户信息", content))

    def _admin_user_calendar(
        self,
        session: sqlite3.Row,
        csrf_token: str,
        user_id: int,
        month_value: str | None,
    ) -> WebResponse:
        today = datetime.now(CHINA_TIMEZONE).date()
        if month_value:
            try:
                selected_month = datetime.strptime(month_value, "%Y-%m").date()
            except ValueError:
                return WebResponse(
                    400,
                    self._page(
                        "月份格式错误",
                        "<main class='login card'><h1>月份格式错误</h1>"
                        "<p class='hint'>month 必须使用 YYYY-MM 格式。</p></main>",
                    ),
                )
            if selected_month.strftime("%Y-%m") != month_value:
                return WebResponse(
                    400,
                    self._page(
                        "月份格式错误",
                        "<main class='login card'><h1>月份格式错误</h1>"
                        "<p class='hint'>month 必须使用 YYYY-MM 格式。</p></main>",
                    ),
                )
        else:
            selected_month = today.replace(day=1)

        selected_month = selected_month.replace(day=1)
        previous_month = (selected_month - timedelta(days=1)).replace(day=1)
        next_month = (selected_month + timedelta(days=32)).replace(day=1)
        date_prefix = selected_month.strftime("%Y%m")

        with self._connection() as connection:
            user = connection.execute(
                "SELECT id, name FROM users WHERE id = ?",
                (user_id,),
            ).fetchone()
            if user is None:
                return WebResponse(
                    404,
                    self._page(
                        "用户不存在",
                        "<main class='login card'><h1>用户不存在</h1>"
                        f"<p class='hint'><a class='action-link' href='{PUBLIC_PREFIX}/admin/'>"
                        "返回用户列表</a></p></main>",
                    ),
                )
            rows = connection.execute(
                """
                SELECT count_date, count
                FROM tasbeeh_daily_counts
                WHERE user_id = ? AND substr(count_date, 1, 6) = ?
                ORDER BY count_date
                """,
                (user_id, date_prefix),
            ).fetchall()

        counts = {str(row["count_date"]): int(row["count"]) for row in rows}
        month_total = sum(counts.values())
        weeks = calendar.Calendar(firstweekday=0).monthdayscalendar(
            selected_month.year,
            selected_month.month,
        )
        calendar_rows = []
        for week in weeks:
            cells = []
            for day in week:
                if day == 0:
                    cells.append("<td class='outside'></td>")
                    continue
                count_date = f"{date_prefix}{day:02d}"
                count = counts.get(count_date, 0)
                today_class = (
                    " today"
                    if (
                        selected_month.year == today.year
                        and selected_month.month == today.month
                        and day == today.day
                    )
                    else ""
                )
                cells.append(
                    f"<td class='{today_class.strip()}'>"
                    f"<div class='calendar-day'>{day}</div>"
                    f"<div class='calendar-count'>{count}</div>"
                    "<div class='calendar-unit'>赞念数</div></td>"
                )
            calendar_rows.append("<tr>" + "".join(cells) + "</tr>")

        escaped_user_name = html.escape(str(user["name"]))
        content = (
            "<header class='top'><div class='wrap toolbar'><div>"
            "<div class='brand'>smartRing 赞念日历</div>"
            f"<div class='sub'>用户：{escaped_user_name}（ID {user_id}） · "
            f"管理员：{html.escape(str(session['name']))}</div></div>"
            "<div class='toolbar-actions'>"
            f"<a class='ghost-link' href='{PUBLIC_PREFIX}/admin/'>返回用户列表</a>"
            f"<form method='post' action='{PUBLIC_PREFIX}/admin/logout'>"
            f"<input type='hidden' name='csrf' value='{html.escape(csrf_token)}'>"
            "<button class='ghost' type='submit'>退出登录</button></form></div></div></header>"
            "<main class='wrap main'><section class='stats'>"
            f"<div class='card stat'><span>{selected_month.strftime('%Y年%m月')}赞念总数</span>"
            f"<strong>{month_total}</strong></div></section>"
            "<section class='card table-card'><div class='table-scroll'>"
            "<div class='calendar-nav'>"
            f"<a class='month-link' href='{PUBLIC_PREFIX}/admin/user?id={user_id}&amp;"
            f"month={previous_month.strftime('%Y-%m')}'>← 上月</a>"
            f"<h1>{selected_month.strftime('%Y年%m月')}</h1>"
            f"<a class='month-link' href='{PUBLIC_PREFIX}/admin/user?id={user_id}&amp;"
            f"month={next_month.strftime('%Y-%m')}'>下月 →</a></div>"
            "<table class='calendar-table'><thead><tr>"
            "<th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th>"
            "<th>星期五</th><th>星期六</th><th>星期日</th>"
            "</tr></thead><tbody>"
            + "".join(calendar_rows)
            + "</tbody></table></div></section></main>"
        )
        return WebResponse(200, self._page(f"{escaped_user_name}的赞念日历", content))

    def _admin_login(self) -> WebResponse:
        form = self._form_body()
        name = form.get("name", "")
        password = form.get("passwd", "")
        with self._connection() as connection:
            admin = connection.execute(
                "SELECT id, passwd_md5 FROM admin_users WHERE name = ?", (name,)
            ).fetchone()
            actual_hash = password_md5(password)
            if admin is None or not hmac.compare_digest(str(admin["passwd_md5"]), actual_hash):
                time.sleep(0.2)
                return self._admin_login_page("用户名或密码错误")
            token = secrets.token_urlsafe(32)
            csrf_token = secrets.token_urlsafe(24)
            now = int(time.time())
            connection.execute(
                "DELETE FROM admin_sessions WHERE expires_at <= ? OR revoked_at IS NOT NULL",
                (now,),
            )
            connection.execute(
                """
                INSERT INTO admin_sessions(
                    admin_user_id, token_hash, csrf_hash, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                (
                    int(admin["id"]),
                    token_hash(token),
                    token_hash(csrf_token),
                    now,
                    now + ADMIN_TOKEN_TTL_SECONDS,
                ),
            )
        cookie = (
            f"{ADMIN_COOKIE_NAME}={token}; Path={PUBLIC_PREFIX}/admin; "
            f"Max-Age={ADMIN_TOKEN_TTL_SECONDS}; Secure; HttpOnly; SameSite=Strict"
        )
        return WebResponse(
            303,
            "",
            [("Location", f"{PUBLIC_PREFIX}/admin/"), ("Set-Cookie", cookie)],
        )

    def _admin_logout(self, session: sqlite3.Row) -> WebResponse:
        form = self._form_body()
        csrf_token = form.get("csrf", "")
        if not csrf_token or not hmac.compare_digest(
            str(session["csrf_hash"]), token_hash(csrf_token)
        ):
            return WebResponse(403, self._page("请求无效", "<main class='login card'><h1>请求无效</h1></main>"))
        with self._connection() as connection:
            connection.execute(
                "UPDATE admin_sessions SET revoked_at = ? WHERE id = ?",
                (int(time.time()), int(session["id"])),
            )
        expired_cookie = (
            f"{ADMIN_COOKIE_NAME}=; Path={PUBLIC_PREFIX}/admin; Max-Age=0; "
            "Secure; HttpOnly; SameSite=Strict"
        )
        return WebResponse(
            303,
            "",
            [("Location", f"{PUBLIC_PREFIX}/admin/"), ("Set-Cookie", expired_cookie)],
        )

    @staticmethod
    def _request_token() -> str:
        authorization = request.headers.get("Authorization", "")

        scheme, separator, token = authorization.strip().partition(" ")
        if not separator or scheme.lower() != "bearer" or not token.strip():
            raise ApiError(401, "UNAUTHORIZED", "缺少有效的 Bearer token")
        return token.strip()

    def _authenticated_user_id(self) -> int:
        token = self._request_token()

        now = int(time.time())
        with self._connection() as connection:
            row = connection.execute(
                """
                SELECT user_id
                FROM sessions
                WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
                """,
                (token_hash(token), now),
            ).fetchone()
        if row is None:
            raise ApiError(401, "UNAUTHORIZED", "token 无效或已过期")
        return int(row["user_id"])

    def _register(self, body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        name, password = self._credentials(body)
        try:
            with self._connection() as connection:
                connection.execute(
                    "INSERT INTO users(name, passwd_md5, created_at) VALUES (?, ?, ?)",
                    (name, password_md5(password), int(time.time())),
                )
        except sqlite3.IntegrityError as exc:
            raise ApiError(409, "USER_EXISTS", "用户名已存在") from exc
        return 201, {"message": "注册成功"}

    def _login(self, body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        name, password = self._credentials(body)
        with self._connection() as connection:
            user = connection.execute(
                "SELECT id, passwd_md5 FROM users WHERE name = ?", (name,)
            ).fetchone()
            actual_hash = password_md5(password)
            if user is None or not hmac.compare_digest(str(user["passwd_md5"]), actual_hash):
                raise ApiError(401, "LOGIN_FAILED", "用户名或密码错误")

            token = secrets.token_urlsafe(32)
            now = int(time.time())
            expires_at = now + TOKEN_TTL_SECONDS
            connection.execute(
                """
                INSERT INTO sessions(user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """,
                (int(user["id"]), token_hash(token), now, expires_at),
            )
        return 200, {
            "token": token,
            "tokenType": "Bearer",
            "expiresAt": utc_iso(expires_at),
            "userId": int(user["id"]),
        }

    def _me(self) -> tuple[int, dict[str, Any]]:
        user_id = self._authenticated_user_id()
        with self._connection() as connection:
            user = connection.execute(
                "SELECT id, name FROM users WHERE id = ?", (user_id,)
            ).fetchone()
        if user is None:
            raise ApiError(401, "UNAUTHORIZED", "token 对应的用户不存在")
        return 200, {"userId": int(user["id"]), "name": str(user["name"])}

    def _logout(self) -> tuple[int, dict[str, Any]]:
        user_id = self._authenticated_user_id()
        token = self._request_token()
        with self._connection() as connection:
            connection.execute(
                """
                UPDATE sessions
                SET revoked_at = ?
                WHERE user_id = ? AND token_hash = ? AND revoked_at IS NULL
                """,
                (int(time.time()), user_id, token_hash(token)),
            )
        return 200, {"message": "注销成功"}

    def _tasbeeh_reset(self) -> tuple[int, dict[str, Any]]:
        user_id = self._authenticated_user_id()
        with self._connection() as connection:
            connection.execute(
                "UPDATE users SET tasbeeh_reset_pending = 1 WHERE id = ?",
                (user_id,),
            )
        return 200, {"message": "重置状态已设置", "reset": True}

    def _tasbeeh_sync(
        self, body: dict[str, Any]
    ) -> tuple[int, dict[str, Any]]:
        count = self._tasbeeh_count(body)
        user_id = self._authenticated_user_id()
        count_date = self._china_date()
        now = int(time.time())

        with self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            user = connection.execute(
                "SELECT tasbeeh_reset_pending FROM users WHERE id = ?",
                (user_id,),
            ).fetchone()
            if user is None:
                raise ApiError(401, "UNAUTHORIZED", "token 对应的用户不存在")

            existing = connection.execute(
                """
                SELECT count
                FROM tasbeeh_daily_counts
                WHERE user_id = ? AND count_date = ?
                """,
                (user_id, count_date),
            ).fetchone()
            existing_count = int(existing["count"]) if existing is not None else 0
            reset_pending = bool(user["tasbeeh_reset_pending"])
            final_count = existing_count + count if reset_pending else count
            if final_count > MAX_TASBEEH_COUNT:
                raise ApiError(
                    400,
                    "COUNT_OVERFLOW",
                    f"当天赞念数不能超过 {MAX_TASBEEH_COUNT}",
                )

            connection.execute(
                """
                INSERT INTO tasbeeh_daily_counts(
                    user_id, count_date, count, updated_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(user_id, count_date) DO UPDATE SET
                    count = excluded.count,
                    updated_at = excluded.updated_at
                """,
                (user_id, count_date, final_count, now),
            )
            connection.execute(
                "UPDATE users SET tasbeeh_reset_pending = 0 WHERE id = ?",
                (user_id,),
            )

        return 200, {
            "message": "同步成功",
            "date": count_date,
            "count": str(final_count),
            "reset": False,
        }

    def _tasbeeh_daily(self) -> tuple[int, dict[str, Any]]:
        user_id = self._authenticated_user_id()
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT count_date, count
                FROM tasbeeh_daily_counts
                WHERE user_id = ?
                ORDER BY count_date DESC
                """,
                (user_id,),
            ).fetchall()
        return 200, {
            "all": [
                {
                    "date": str(row["count_date"]),
                    "count": str(row["count"]),
                }
                for row in rows
            ]
        }

    def _create_blessing_tag(
        self, body: dict[str, Any]
    ) -> tuple[int, dict[str, Any]]:
        owner_user_id = self._authenticated_user_id()
        nickname = self._required_text(
            body,
            "nickname",
            MAX_BLESSING_NICKNAME_LENGTH,
            "INVALID_NICKNAME",
        )
        message = self._required_text(
            body,
            "message",
            MAX_BLESSING_MESSAGE_LENGTH,
            "INVALID_BLESSING_MESSAGE",
        )
        package_name = self._required_text(
            body,
            "packageName",
            MAX_PACKAGE_NAME_LENGTH,
            "INVALID_PACKAGE_NAME",
        )
        blessing_id = str(uuid.uuid4())
        created_at = int(time.time())
        with self._connection() as connection:
            connection.execute(
                """
                INSERT INTO blessing_tags(
                    id, owner_user_id, nickname, message, package_name, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    blessing_id,
                    owner_user_id,
                    nickname,
                    message,
                    package_name,
                    created_at,
                ),
            )
        return 201, {
            "blessingId": blessing_id,
            "senderUserId": owner_user_id,
            "nickname": nickname,
            "message": message,
            "packageName": package_name,
            "createdAt": utc_iso(created_at),
        }

    def _blessing_tag(self, blessing_id: str) -> tuple[int, dict[str, Any]]:
        if not blessing_id or len(blessing_id) > 64 or blessing_id != blessing_id.strip():
            raise ApiError(400, "INVALID_BLESSING_ID", "blessingId 格式不正确")
        with self._connection() as connection:
            row = connection.execute(
                """
                SELECT t.id, t.owner_user_id, t.nickname, t.message,
                       t.package_name, t.created_at, u.name AS sender_name
                FROM blessing_tags AS t
                JOIN users AS u ON u.id = t.owner_user_id
                WHERE t.id = ?
                """,
                (blessing_id,),
            ).fetchone()
        if row is None:
            raise ApiError(404, "BLESSING_NOT_FOUND", "祈福贴纸未在服务端登记")
        return 200, {
            "blessingId": str(row["id"]),
            "senderUserId": int(row["owner_user_id"]),
            "senderName": str(row["sender_name"]),
            "nickname": str(row["nickname"]),
            "message": str(row["message"]),
            "packageName": str(row["package_name"]),
            "createdAt": utc_iso(int(row["created_at"])),
        }

    @staticmethod
    def _blessing_event_payload(row: sqlite3.Row) -> dict[str, Any]:
        sender_user_id = int(row["sender_user_id"])
        recipient_user_id = int(row["recipient_user_id"])
        return {
            "eventId": int(row["event_id"]),
            "blessingId": str(row["blessing_id"]),
            "nickname": str(row["nickname"]),
            "message": str(row["message"]),
            "packageName": str(row["package_name"]),
            "senderUserId": sender_user_id,
            "senderName": str(row["sender_name"]),
            "recipientUserId": recipient_user_id,
            "recipientName": str(row["recipient_name"]),
            "createdAt": utc_iso(int(row["created_at"])),
            "isSelf": sender_user_id == recipient_user_id,
        }

    def _blessing_event_row(
        self, connection: sqlite3.Connection, event_key: str
    ) -> sqlite3.Row:
        row = connection.execute(
            """
            SELECT e.id AS event_id, e.created_at, e.recipient_user_id,
                   t.id AS blessing_id, t.nickname, t.message, t.package_name,
                   t.owner_user_id AS sender_user_id,
                   sender.name AS sender_name, recipient.name AS recipient_name
            FROM blessing_events AS e
            JOIN blessing_tags AS t ON t.id = e.blessing_id
            JOIN users AS sender ON sender.id = t.owner_user_id
            JOIN users AS recipient ON recipient.id = e.recipient_user_id
            WHERE e.event_key = ?
            """,
            (event_key,),
        ).fetchone()
        if row is None:
            raise ApiError(500, "EVENT_NOT_FOUND", "祈福事件保存失败")
        return row

    def _receive_blessing(
        self, body: dict[str, Any]
    ) -> tuple[int, dict[str, Any]]:
        recipient_user_id = self._authenticated_user_id()
        blessing_id = self._required_text(
            body, "blessingId", 64, "INVALID_BLESSING_ID"
        )
        event_key = self._required_text(
            body,
            "eventId",
            MAX_BLESSING_EVENT_KEY_LENGTH,
            "INVALID_EVENT_ID",
        )
        created_at = int(time.time())
        with self._connection() as connection:
            tag = connection.execute(
                "SELECT id FROM blessing_tags WHERE id = ?", (blessing_id,)
            ).fetchone()
            if tag is None:
                raise ApiError(404, "BLESSING_NOT_FOUND", "祈福贴纸未在服务端登记")
            cursor = connection.execute(
                """
                INSERT INTO blessing_events(
                    event_key, blessing_id, recipient_user_id, created_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(event_key) DO NOTHING
                """,
                (event_key, blessing_id, recipient_user_id, created_at),
            )
            duplicate = cursor.rowcount == 0
            event = self._blessing_event_row(connection, event_key)
            if (
                str(event["blessing_id"]) != blessing_id
                or int(event["recipient_user_id"]) != recipient_user_id
            ):
                raise ApiError(409, "EVENT_ID_CONFLICT", "eventId 已被其他祈福事件使用")
        payload = self._blessing_event_payload(event)
        return 200, {"duplicate": duplicate, "event": payload}

    def _blessing_history(self) -> tuple[int, dict[str, Any]]:
        user_id = self._authenticated_user_id()
        query = """
            SELECT e.id AS event_id, e.created_at, e.recipient_user_id,
                   t.id AS blessing_id, t.nickname, t.message, t.package_name,
                   t.owner_user_id AS sender_user_id,
                   sender.name AS sender_name, recipient.name AS recipient_name
            FROM blessing_events AS e
            JOIN blessing_tags AS t ON t.id = e.blessing_id
            JOIN users AS sender ON sender.id = t.owner_user_id
            JOIN users AS recipient ON recipient.id = e.recipient_user_id
        """
        with self._connection() as connection:
            sent_rows = connection.execute(
                query
                + " WHERE t.owner_user_id = ?"
                + " ORDER BY e.created_at DESC, e.id DESC LIMIT 200",
                (user_id,),
            ).fetchall()
            received_rows = connection.execute(
                query
                + " WHERE e.recipient_user_id = ?"
                + " ORDER BY e.created_at DESC, e.id DESC LIMIT 200",
                (user_id,),
            ).fetchall()
        return 200, {
            "sent": [self._blessing_event_payload(row) for row in sent_rows],
            "received": [self._blessing_event_payload(row) for row in received_rows],
        }

def create_app(
    database_path: str | os.PathLike[str] | None = None,
    apk_path: str | os.PathLike[str] | None = None,
) -> Flask:
    """Create an isolated Flask application backed by the requested database."""
    resolved_database_path = database_path or os.environ.get(
        "DATABASE_PATH", str(Path(__file__).with_name("data") / "smartring.db")
    )
    resolved_apk_path = apk_path or os.environ.get(
        "APP_APK_PATH", str(Path(__file__).with_name("app") / "sr.apk")
    )
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = MAX_BODY_BYTES
    app.config["APP_APK_PATH"] = str(resolved_apk_path)
    app.json.ensure_ascii = False
    app.json.compact = True
    service = SmartRingService(resolved_database_path)
    app.extensions["smartring_service"] = service

    def add_dual_rule(
        rule: str,
        endpoint: str,
        view_func: Any,
        methods: list[str],
    ) -> None:
        """Serve both Nginx-stripped paths and direct /smartRing paths."""
        app.add_url_rule(
            rule,
            endpoint=endpoint,
            view_func=view_func,
            methods=methods,
            strict_slashes=False,
        )
        prefixed_rule = f"{PUBLIC_PREFIX}/" if rule == "/" else f"{PUBLIC_PREFIX}{rule}"
        app.add_url_rule(
            prefixed_rule,
            endpoint=f"{endpoint}_prefixed",
            view_func=view_func,
            methods=methods,
            strict_slashes=False,
        )

    def api_result(result: tuple[int, dict[str, Any]]) -> tuple[Response, int]:
        status, payload = result
        return jsonify(payload), status

    def html_result(result: WebResponse) -> Response:
        response = make_response(result.body, result.status)
        response.headers["Content-Type"] = "text/html; charset=utf-8"
        for name, value in result.headers:
            response.headers.add(name, value)
        return response

    def download_apk() -> Response:
        artifact_path = Path(app.config["APP_APK_PATH"])
        if not artifact_path.is_file():
            raise ApiError(404, "APP_NOT_AVAILABLE", "APP 安装包暂未发布")
        return send_file(
            artifact_path,
            mimetype="application/vnd.android.package-archive",
            as_attachment=True,
            download_name="sr.apk",
            conditional=True,
        )

    def register() -> tuple[Response, int]:
        return api_result(service._register(service._json_body()))

    def login() -> tuple[Response, int]:
        return api_result(service._login(service._json_body()))

    def logout() -> tuple[Response, int]:
        service._json_body()
        return api_result(service._logout())

    def me() -> tuple[Response, int]:
        return api_result(service._me())

    def tasbeeh_reset() -> tuple[Response, int]:
        service._json_body()
        return api_result(service._tasbeeh_reset())

    def tasbeeh_sync() -> tuple[Response, int]:
        return api_result(service._tasbeeh_sync(service._json_body()))

    def tasbeeh_daily() -> tuple[Response, int]:
        return api_result(service._tasbeeh_daily())

    def create_blessing_tag() -> tuple[Response, int]:
        return api_result(service._create_blessing_tag(service._json_body()))

    def blessing_tag(blessing_id: str) -> tuple[Response, int]:
        return api_result(service._blessing_tag(blessing_id))

    def receive_blessing() -> tuple[Response, int]:
        return api_result(service._receive_blessing(service._json_body()))

    def blessing_history() -> tuple[Response, int]:
        return api_result(service._blessing_history())

    def admin_dashboard() -> Response:
        session = service._admin_session()
        if session is None:
            return html_result(service._admin_login_page())
        csrf_token = secrets.token_urlsafe(24)
        with service._connection() as connection:
            connection.execute(
                "UPDATE admin_sessions SET csrf_hash = ? WHERE id = ?",
                (token_hash(csrf_token), int(session["id"])),
            )
        return html_result(service._admin_dashboard(session, csrf_token))

    def admin_login() -> Response:
        return html_result(service._admin_login())

    def admin_user_calendar() -> Response:
        session = service._admin_session()
        if session is None:
            return html_result(service._admin_login_page())
        user_id_value = request.args.get("id", "")
        if not user_id_value.isascii() or not user_id_value.isdigit():
            return html_result(
                WebResponse(
                    400,
                    service._page(
                        "用户参数错误",
                        "<main class='login card'><h1>用户参数错误</h1>"
                        "<p class='hint'>id 必须是正整数。</p></main>",
                    ),
                )
            )
        user_id = int(user_id_value)
        if user_id <= 0:
            return html_result(
                WebResponse(
                    400,
                    service._page(
                        "用户参数错误",
                        "<main class='login card'><h1>用户参数错误</h1>"
                        "<p class='hint'>id 必须是正整数。</p></main>",
                    ),
                )
            )
        csrf_token = secrets.token_urlsafe(24)
        with service._connection() as connection:
            connection.execute(
                "UPDATE admin_sessions SET csrf_hash = ? WHERE id = ?",
                (token_hash(csrf_token), int(session["id"])),
            )
        return html_result(
            service._admin_user_calendar(
                session,
                csrf_token,
                user_id,
                request.args.get("month"),
            )
        )

    def admin_logout() -> Response:
        session = service._admin_session()
        if session is None:
            return html_result(service._admin_login_page())
        return html_result(service._admin_logout(session))

    add_dual_rule("/app/sr.apk", "app_apk", download_apk, ["GET"])
    add_dual_rule("/regist", "regist", register, ["POST"])
    add_dual_rule("/register", "register", register, ["POST"])
    add_dual_rule("/login", "login", login, ["POST"])
    add_dual_rule("/logout", "logout", logout, ["POST"])
    add_dual_rule("/me", "me", me, ["GET"])
    add_dual_rule("/tasbeeh/reset", "tasbeeh_reset", tasbeeh_reset, ["POST"])
    add_dual_rule("/tasbeeh/sync", "tasbeeh_sync", tasbeeh_sync, ["POST"])
    add_dual_rule("/tasbeeh/daily", "tasbeeh_daily", tasbeeh_daily, ["GET"])
    add_dual_rule(
        "/blessings/tags", "create_blessing_tag", create_blessing_tag, ["POST"]
    )
    add_dual_rule(
        "/blessings/tags/<string:blessing_id>",
        "blessing_tag",
        blessing_tag,
        ["GET"],
    )
    add_dual_rule(
        "/blessings/receive", "receive_blessing", receive_blessing, ["POST"]
    )
    add_dual_rule("/blessings", "blessing_history", blessing_history, ["GET"])
    add_dual_rule("/admin/", "admin_dashboard", admin_dashboard, ["GET"])
    add_dual_rule("/admin/user", "admin_user_calendar", admin_user_calendar, ["GET"])
    add_dual_rule("/admin/login", "admin_login", admin_login, ["POST"])
    add_dual_rule("/admin/logout", "admin_logout", admin_logout, ["POST"])

    @app.errorhandler(ApiError)
    def handle_api_error(error: ApiError) -> tuple[Response, int]:
        return jsonify({"code": error.code, "message": error.message}), error.status

    @app.errorhandler(404)
    @app.errorhandler(405)
    def handle_not_found(_error: Exception) -> tuple[Response, int]:
        return jsonify({"code": "NOT_FOUND", "message": "接口不存在"}), 404

    @app.errorhandler(413)
    def handle_body_too_large(_error: Exception) -> tuple[Response, int]:
        return jsonify({"code": "BODY_TOO_LARGE", "message": "请求体不能超过 16 KiB"}), 413

    @app.errorhandler(500)
    def handle_internal_error(error: Exception) -> tuple[Response, int]:
        LOGGER.error("Unhandled Flask error: %s", error)
        return jsonify({"code": "INTERNAL_ERROR", "message": "服务器内部错误"}), 500

    @app.after_request
    def apply_security_headers(response: Response) -> Response:
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        if response.mimetype == "text/html":
            response.headers["X-Frame-Options"] = "DENY"
            response.headers["Referrer-Policy"] = "no-referrer"
            content_security_policy = (
                "default-src 'none'; style-src 'unsafe-inline'; "
                "form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
            )
            response.headers["Content-Security-Policy"] = content_security_policy
        return response

    return app


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    database_path = os.environ.get(
        "DATABASE_PATH", str(Path(__file__).with_name("data") / "smartring.db")
    )
    if len(sys.argv) > 1 and sys.argv[1] == "create-admin":
        parser = ArgumentParser(description="Create or update a smartRing administrator")
        parser.add_argument("create-admin")
        parser.add_argument("--name", required=True)
        arguments = parser.parse_args()
        password = getpass("管理员密码：")
        service = SmartRingService(database_path)
        service.set_admin(arguments.name, password)
        print(f"管理员 {arguments.name} 已创建或更新")
        return

    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8001"))
    app = create_app(database_path)
    LOGGER.info("smartRing Flask development server listening on http://%s:%s", host, port)
    app.run(host=host, port=port, debug=False, use_reloader=False)


if __name__ == "__main__":
    main()
