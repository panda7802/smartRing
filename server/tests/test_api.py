import hashlib
import json
import re
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from flask import Flask

from app import SmartRingService, create_app


class ApiClient:
    def __init__(self, app: Flask) -> None:
        self.client = app.test_client()

    def request(self, method, path, body=None, token=None, form=None, cookie=None):
        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if form is not None:
            data = form
            content_type = "application/x-www-form-urlencoded"
        else:
            data = None if body is None else json.dumps(body).encode("utf-8")
            content_type = "application/json" if body is not None else ""
        # Flask's test client maintains the admin cookie jar. The cookie argument
        # remains accepted so the tests read like real browser calls.
        _ = cookie
        flask_response = self.client.open(
            path,
            method=method,
            data=data,
            headers=headers,
            content_type=content_type or None,
            base_url="https://www.panzhenghao.cn",
        )
        response = {
            "status": flask_response.status_code,
            "headers": dict(flask_response.headers),
            "text": flask_response.get_data(as_text=True),
        }
        if flask_response.is_json:
            response["json"] = flask_response.get_json()
        return response


class SmartRingApiTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tempdir.name) / "test.db"
        self.app = create_app(self.db_path)
        self.app.config["TESTING"] = True
        self.service: SmartRingService = self.app.extensions["smartring_service"]
        self.client = ApiClient(self.app)

    def tearDown(self):
        self.tempdir.cleanup()

    def register_and_login(self, name="alice", password="secret"):
        registered = self.client.request(
            "POST", "/regist", {"name": name, "passwd": password}
        )
        self.assertEqual(201, registered["status"])
        logged_in = self.client.request(
            "POST", "/login", {"name": name, "passwd": password}
        )
        self.assertEqual(200, logged_in["status"])
        return logged_in["json"]["token"]

    def test_flask_factory_only_registers_supported_routes(self):
        self.assertIsInstance(self.app, Flask)
        rules = {rule.rule for rule in self.app.url_map.iter_rules()}
        for route in (
            "/login",
            "/smartRing/login",
            "/logout",
            "/smartRing/logout",
            "/tasbeeh/reset",
            "/smartRing/tasbeeh/reset",
            "/tasbeeh/sync",
            "/smartRing/tasbeeh/sync",
            "/tasbeeh/daily",
            "/smartRing/tasbeeh/daily",
            "/admin/",
            "/smartRing/admin/",
            "/admin/user",
            "/smartRing/admin/user",
            "/app/sr.apk",
            "/smartRing/app/sr.apk",
        ):
            self.assertIn(route, rules)
        for removed_route in (
            "/",
            "/smartRing/",
            "/health",
            "/smartRing/health",
            "/tasbeeh",
            "/smartRing/tasbeeh",
            "/histasbeeh",
            "/smartRing/histasbeeh",
            "/tasbeeh/list",
            "/smartRing/tasbeeh/list",
        ):
            self.assertNotIn(removed_route, rules)

    def test_removed_endpoints_return_not_found(self):
        cases = (
            ("GET", "/", None),
            ("GET", "/smartRing/", None),
            ("GET", "/health", None),
            ("GET", "/smartRing/health", None),
            ("POST", "/tasbeeh", {"tasbeehTime": "20260717112233"}),
            ("POST", "/smartRing/tasbeeh", {"tasbeehTime": "20260717112233"}),
            ("POST", "/histasbeeh", {}),
            ("GET", "/tasbeeh/list", None),
            ("POST", "/tasbeeh/list", {}),
            ("GET", "/smartRing/tasbeeh/list", None),
        )
        for method, path, body in cases:
            with self.subTest(method=method, path=path):
                response = self.client.request(method, path, body=body)
                self.assertEqual(404, response["status"])
                self.assertEqual("NOT_FOUND", response["json"]["code"])

    def test_database_only_contains_user_related_tables(self):
        with closing(sqlite3.connect(self.db_path)) as connection:
            tables = {
                row[0]
                for row in connection.execute(
                    """
                    SELECT name FROM sqlite_master
                    WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                    """
                )
            }
        self.assertEqual(
            {
                "users",
                "sessions",
                "admin_users",
                "admin_sessions",
                "tasbeeh_daily_counts",
            },
            tables,
        )

    def test_legacy_tasbeeh_table_is_removed_during_migration(self):
        legacy_path = Path(self.tempdir.name) / "legacy.db"
        with closing(sqlite3.connect(legacy_path)) as connection:
            connection.execute(
                """
                CREATE TABLE tasbeeh_records (
                    id INTEGER PRIMARY KEY,
                    tasbeeh_time TEXT NOT NULL
                )
                """
            )
            connection.execute(
                "INSERT INTO tasbeeh_records(tasbeeh_time) VALUES (?)",
                ("20260717112233",),
            )
            connection.commit()

        create_app(legacy_path)

        with closing(sqlite3.connect(legacy_path)) as connection:
            table = connection.execute(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name = 'tasbeeh_records'
                """
            ).fetchone()
        self.assertIsNone(table)

    def test_existing_users_are_migrated_to_default_reset_state(self):
        legacy_path = Path(self.tempdir.name) / "users-legacy.db"
        with closing(sqlite3.connect(legacy_path)) as connection:
            connection.execute(
                """
                CREATE TABLE users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE COLLATE BINARY,
                    passwd_md5 TEXT NOT NULL CHECK(length(passwd_md5) = 32),
                    created_at INTEGER NOT NULL
                )
                """
            )
            connection.execute(
                """
                INSERT INTO users(name, passwd_md5, created_at)
                VALUES (?, ?, ?)
                """,
                ("existing", hashlib.md5(b"secret").hexdigest(), 1),
            )
            connection.commit()

        create_app(legacy_path)

        with closing(sqlite3.connect(legacy_path)) as connection:
            reset_pending = connection.execute(
                """
                SELECT tasbeeh_reset_pending
                FROM users
                WHERE name = ?
                """,
                ("existing",),
            ).fetchone()[0]
        self.assertEqual(1, reset_pending)

    def test_register_stores_md5_and_duplicate_is_rejected(self):
        response = self.client.request(
            "POST", "/regist", {"name": "alice", "passwd": "secret"}
        )
        self.assertEqual(201, response["status"])
        with closing(sqlite3.connect(self.db_path)) as connection:
            stored = connection.execute(
                "SELECT passwd_md5 FROM users WHERE name = ?", ("alice",)
            ).fetchone()[0]
        self.assertEqual(hashlib.md5(b"secret").hexdigest(), stored)

        duplicate = self.client.request(
            "POST", "/regist", {"name": "alice", "passwd": "different"}
        )
        self.assertEqual(409, duplicate["status"])

    def test_login_and_logout_flow(self):
        token = self.register_and_login()
        logged_out = self.client.request("POST", "/logout", {}, token)
        self.assertEqual(200, logged_out["status"])
        second_logout = self.client.request("POST", "/logout", {}, token)
        self.assertEqual(401, second_logout["status"])

    def test_wrong_password_and_missing_token_are_rejected(self):
        self.register_and_login()
        wrong = self.client.request(
            "POST", "/login", {"name": "alice", "passwd": "wrong"}
        )
        self.assertEqual(401, wrong["status"])
        no_token = self.client.request("POST", "/logout", {})
        self.assertEqual(401, no_token["status"])

    def test_compatibility_register_route_remains_available(self):
        registered = self.client.request(
            "POST", "/smartRing/register", {"name": "legacy", "passwd": "secret"}
        )
        self.assertEqual(201, registered["status"])
        token = self.client.request(
            "POST", "/smartRing/login", {"name": "legacy", "passwd": "secret"}
        )["json"]["token"]
        logged_out = self.client.request(
            "POST", "/smartRing/logout", {}, token=token
        )
        self.assertEqual(200, logged_out["status"])

    def test_tasbeeh_sync_adds_after_reset_then_overwrites(self):
        token = self.register_and_login()

        first = self.client.request(
            "POST", "/smartRing/tasbeeh/sync", {"count": "5"}, token
        )
        self.assertEqual(200, first["status"])
        self.assertEqual("5", first["json"]["count"])
        self.assertFalse(first["json"]["reset"])

        second = self.client.request(
            "POST", "/smartRing/tasbeeh/sync", {"count": 7}, token
        )
        self.assertEqual(200, second["status"])
        self.assertEqual("7", second["json"]["count"])

        reset = self.client.request(
            "POST", "/smartRing/tasbeeh/reset", {}, token
        )
        self.assertEqual(200, reset["status"])
        self.assertTrue(reset["json"]["reset"])

        third = self.client.request(
            "POST", "/smartRing/tasbeeh/sync", {"count": "3"}, token
        )
        self.assertEqual(200, third["status"])
        self.assertEqual("10", third["json"]["count"])

        with closing(sqlite3.connect(self.db_path)) as connection:
            stored_count = connection.execute(
                "SELECT count FROM tasbeeh_daily_counts"
            ).fetchone()[0]
            reset_pending = connection.execute(
                """
                SELECT tasbeeh_reset_pending
                FROM users
                WHERE name = ?
                """,
                ("alice",),
            ).fetchone()[0]
        self.assertEqual(10, stored_count)
        self.assertEqual(0, reset_pending)

    def test_tasbeeh_data_is_independent_for_each_user(self):
        alice_token = self.register_and_login("alice", "secret")
        bob_token = self.register_and_login("bob", "secret")

        alice = self.client.request(
            "POST", "/tasbeeh/sync", {"count": "12"}, alice_token
        )
        bob = self.client.request(
            "POST", "/tasbeeh/sync", {"count": "4"}, bob_token
        )
        self.assertEqual("12", alice["json"]["count"])
        self.assertEqual("4", bob["json"]["count"])

        with closing(sqlite3.connect(self.db_path)) as connection:
            rows = dict(
                connection.execute(
                    """
                    SELECT users.name, tasbeeh_daily_counts.count
                    FROM tasbeeh_daily_counts
                    JOIN users ON users.id = tasbeeh_daily_counts.user_id
                    """
                ).fetchall()
            )
        self.assertEqual({"alice": 12, "bob": 4}, rows)

    def test_tasbeeh_sync_validates_count_and_authentication(self):
        token = self.register_and_login()
        invalid_values = (None, "", "-1", "1.5", 1.5, -1, True, "９")
        for value in invalid_values:
            with self.subTest(value=value):
                response = self.client.request(
                    "POST", "/tasbeeh/sync", {"count": value}, token
                )
                self.assertEqual(400, response["status"])
                self.assertEqual("INVALID_COUNT", response["json"]["code"])

        too_large = self.client.request(
            "POST",
            "/tasbeeh/sync",
            {"count": "9007199254740992"},
            token,
        )
        self.assertEqual(400, too_large["status"])
        self.assertEqual("INVALID_COUNT", too_large["json"]["code"])

        for route, body in (
            ("/tasbeeh/reset", {}),
            ("/tasbeeh/sync", {"count": "1"}),
        ):
            with self.subTest(route=route):
                response = self.client.request("POST", route, body)
                self.assertEqual(401, response["status"])

    def test_tasbeeh_daily_returns_only_current_user_in_date_order(self):
        alice_token = self.register_and_login("alice", "secret")
        self.register_and_login("bob", "secret")
        with closing(sqlite3.connect(self.db_path)) as connection:
            user_ids = dict(
                connection.execute(
                    "SELECT name, id FROM users WHERE name IN (?, ?)",
                    ("alice", "bob"),
                ).fetchall()
            )
            connection.executemany(
                """
                INSERT INTO tasbeeh_daily_counts(
                    user_id, count_date, count, updated_at
                ) VALUES (?, ?, ?, ?)
                """,
                (
                    (user_ids["alice"], "20260724", 4, 1),
                    (user_ids["alice"], "20260725", 7, 2),
                    (user_ids["bob"], "20260726", 99, 3),
                ),
            )
            connection.commit()

        response = self.client.request(
            "GET", "/smartRing/tasbeeh/daily", token=alice_token
        )
        self.assertEqual(200, response["status"])
        self.assertEqual(
            {
                "all": [
                    {"date": "20260725", "count": "7"},
                    {"date": "20260724", "count": "4"},
                ]
            },
            response["json"],
        )

    def test_tasbeeh_daily_returns_empty_list_and_requires_authentication(self):
        token = self.register_and_login()
        empty = self.client.request("GET", "/tasbeeh/daily", token=token)
        self.assertEqual(200, empty["status"])
        self.assertEqual({"all": []}, empty["json"])

        unauthorized = self.client.request("GET", "/tasbeeh/daily")
        self.assertEqual(401, unauthorized["status"])

    def test_apk_download_serves_direct_and_public_routes(self):
        apk_path = Path(self.tempdir.name) / "sr.apk"
        apk_bytes = b"PK\x03\x04test-apk"
        apk_path.write_bytes(apk_bytes)
        app = create_app(self.db_path, apk_path)
        app.config["TESTING"] = True
        client = app.test_client()

        for route in ("/app/sr.apk", "/smartRing/app/sr.apk"):
            response = client.get(route)
            try:
                self.assertEqual(200, response.status_code)
                self.assertEqual(apk_bytes, response.data)
                self.assertEqual(
                    "application/vnd.android.package-archive",
                    response.mimetype,
                )
                self.assertIn("sr.apk", response.headers["Content-Disposition"])
            finally:
                response.close()

    def test_admin_displays_user_information_and_daily_calendar(self):
        self.register_and_login("alice", "secret")
        self.register_and_login("bob", "secret")
        with closing(sqlite3.connect(self.db_path)) as connection:
            user_ids = dict(
                connection.execute(
                    "SELECT name, id FROM users WHERE name IN (?, ?)",
                    ("alice", "bob"),
                ).fetchall()
            )
            connection.executemany(
                """
                INSERT INTO tasbeeh_daily_counts(
                    user_id, count_date, count, updated_at
                ) VALUES (?, ?, ?, ?)
                """,
                (
                    (user_ids["alice"], "20260724", 4, 1),
                    (user_ids["alice"], "20260725", 7, 2),
                    (user_ids["bob"], "20260725", 99, 3),
                ),
            )
            connection.commit()
        self.service.set_admin("pangt", "pangt123")

        protected_calendar = self.client.request(
            "GET",
            f"/smartRing/admin/user?id={user_ids['alice']}&month=2026-07",
        )
        self.assertEqual(200, protected_calendar["status"])
        self.assertIn("smartRing 管理后台", protected_calendar["text"])
        self.assertNotIn("2026年07月赞念总数", protected_calendar["text"])

        login_page = self.client.request("GET", "/smartRing/admin/")
        self.assertEqual(200, login_page["status"])
        self.assertIn("查看全部注册用户信息", login_page["text"])

        wrong = self.client.request(
            "POST",
            "/smartRing/admin/login",
            form={"name": "pangt", "passwd": "wrong"},
        )
        self.assertEqual(401, wrong["status"])

        logged_in = self.client.request(
            "POST",
            "/smartRing/admin/login",
            form={"name": "pangt", "passwd": "pangt123"},
        )
        self.assertEqual(303, logged_in["status"])
        cookie = logged_in["headers"]["Set-Cookie"].split(";", 1)[0]

        dashboard = self.client.request(
            "GET", "/smartRing/admin/", cookie=cookie
        )
        self.assertEqual(200, dashboard["status"])
        self.assertIn("注册用户信息", dashboard["text"])
        self.assertIn("用户 ID", dashboard["text"])
        self.assertIn("alice", dashboard["text"])
        self.assertNotIn("secret", dashboard["text"])
        self.assertIn("赞念记录", dashboard["text"])
        self.assertIn("查看赞念日历", dashboard["text"])
        self.assertNotIn("查看趋势", dashboard["text"])
        self.assertIn(
            f"/smartRing/admin/user?id={user_ids['alice']}",
            dashboard["text"],
        )

        invalid_month = self.client.request(
            "GET",
            f"/smartRing/admin/user?id={user_ids['alice']}&month=2026-13",
            cookie=cookie,
        )
        self.assertEqual(400, invalid_month["status"])

        calendar_page = self.client.request(
            "GET",
            f"/smartRing/admin/user?id={user_ids['alice']}&month=2026-07",
            cookie=cookie,
        )
        self.assertEqual(200, calendar_page["status"])
        self.assertIn("smartRing 赞念日历", calendar_page["text"])
        self.assertIn("用户：alice", calendar_page["text"])
        self.assertIn("2026年07月赞念总数", calendar_page["text"])
        self.assertIn("<strong>11</strong>", calendar_page["text"])
        self.assertIn("<div class='calendar-count'>4</div>", calendar_page["text"])
        self.assertIn("<div class='calendar-count'>7</div>", calendar_page["text"])
        self.assertNotIn("<div class='calendar-count'>99</div>", calendar_page["text"])
        self.assertIn("month=2026-06", calendar_page["text"])
        self.assertIn("month=2026-08", calendar_page["text"])

        csrf = re.search(
            r"name='csrf' value='([^']+)'", calendar_page["text"]
        ).group(1)
        logged_out = self.client.request(
            "POST",
            "/smartRing/admin/logout",
            form={"csrf": csrf},
            cookie=cookie,
        )
        self.assertEqual(303, logged_out["status"])

        with closing(sqlite3.connect(self.db_path)) as connection:
            stored = connection.execute(
                "SELECT passwd_md5 FROM admin_users WHERE name = ?", ("pangt",)
            ).fetchone()[0]
        self.assertEqual(hashlib.md5(b"pangt123").hexdigest(), stored)


if __name__ == "__main__":
    unittest.main()
