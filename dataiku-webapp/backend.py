# ═══════════════════════════════════════════════════════════════════════════
#  Staff Attendance - Dataiku web app backend
#
#  Paste this into the "Python" tab of a Dataiku *Standard* web app with the
#  backend enabled (Settings -> "Python backend" -> Enabled).
#
#  NO LONGER READ-ONLY. It was until 5 Sep 2026; it now writes in exactly two
#  places, and both are deliberately narrow:
#
#    * editing a staff member's details (never their SF ID)
#    * voiding a punch (a marked row, never a DELETE)
#
#  Every write goes through run_write(), which wraps DML in BEGIN/COMMIT.
#  Dataiku connections are not in autocommit, so a bare query_to_df("UPDATE")
#  reports success and changes nothing.
#
#  Nothing here talks to the terminal, and nothing here should: Dataiku mounts
#  these routes under /web-apps-backends/<PROJECT>/<WEBAPP_ID>/ behind Dataiku
#  authentication, which a ZKTeco device cannot satisfy. Edits reach terminals
#  the long way round - UPDATED_AT, publish_roster.py, the roster pull.
# ═══════════════════════════════════════════════════════════════════════════

import logging
import re
import threading
import time
from datetime import datetime

from dataiku.customwebapp import *          # provides `app` and get_webapp_config()
from dataiku.core.sql import SQLExecutor2
from flask import jsonify, request

logger = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────────────
# Prefer webapp config so these can be changed in the UI without editing code.
# Add them under Settings -> Configuration as a JSON object.
_cfg = {}
try:
    _cfg = get_webapp_config() or {}
except Exception:                            # running outside Dataiku (tests)
    pass

# Name of the Snowflake connection in Administration -> Connections - the one
# your Dataiku datasets already write through. Not the database name.
SNOWFLAKE_CONNECTION = _cfg.get("snowflake_connection", "Development")
SCHEMA               = _cfg.get("schema", "DEVELOPMENT.ATTENDANCE")
DEVICE_TZ            = _cfg.get("device_timezone", "Africa/Lagos")
CACHE_TTL_SECONDS    = int(_cfg.get("cache_ttl_seconds", 10))
MAX_ROWS             = 1000

# The dashboard polls. Without a cache, ten open browser tabs at a 5s interval
# keep a Snowflake warehouse permanently resumed for no analytical benefit.
# A short TTL collapses that back to one query per interval regardless of tabs.
_cache = {}
_cache_lock = threading.Lock()


def _cached(key, ttl, producer):
    now = time.time()
    with _cache_lock:
        hit = _cache.get(key)
        if hit and now - hit[0] < ttl:
            return hit[1]
    value = producer()
    with _cache_lock:
        _cache[key] = (now, value)
    return value


# ── Input handling ─────────────────────────────────────────────────────────
# SQLExecutor2 has no bind-parameter API: it sends a finished string. Every
# value interpolated below therefore has to be validated or escaped here.
# Treat this section as security-relevant when editing.

_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def sql_str(value, max_len=128):
    """Escape a user string into a Snowflake literal.

    Snowflake processes backslash escapes inside string literals by default,
    so the backslash must be doubled as well as the quote - escaping only the
    quote leaves a trailing-backslash injection open.
    """
    if value is None:
        return "NULL"
    s = str(value)[:max_len]
    s = s.replace("\\", "\\\\").replace("'", "''")
    return "'" + s + "'"


def safe_date(value):
    """Return a validated YYYY-MM-DD string, or None to mean 'today'."""
    if not value:
        return None
    value = str(value).strip()
    if not _DATE_RE.match(value):
        raise ValueError("date must be YYYY-MM-DD")
    try:
        datetime.strptime(value, "%Y-%m-%d")
    except ValueError:
        raise ValueError("not a real calendar date")
    return value


def safe_limit(value, default=100):
    try:
        n = int(value) if value not in (None, "") else default
    except (TypeError, ValueError):
        return default
    return max(1, min(n, MAX_ROWS))


def today_expr(date_str):
    """SQL expression for the reporting date.

    Punches are stored as TIMESTAMP_NTZ in device-local wall-clock time, so
    'today' must be evaluated in the device's timezone.

    SYSDATE(), not CURRENT_TIMESTAMP(). This function asserts its input is UTC,
    and the comment here used to say a Snowflake warehouse "usually runs in
    UTC". This account does not: its session timezone is Snowflake's default,
    US Pacific, measured at 8 hours off Lagos on 5 Sep 2026. The assertion was
    false and "today" was wrong by hours - invisible for most of the day, and
    wrong for exactly the hour before midnight where it decides which day a
    punch belongs to. SYSDATE() is UTC whatever the session is set to.
    """
    if date_str:
        return "TO_DATE('%s')" % date_str
    return "CONVERT_TIMEZONE('UTC', '%s', SYSDATE())::DATE" % DEVICE_TZ


def logs_where(date_str, date_from, date_to, q, district, all_dates):
    """Build the WHERE clause for the attendance queries.

    Shared by the table and the CSV export so the two cannot drift - an export
    that silently disagrees with what is on screen is worse than no export.

    Date precedence: an explicit range beats a single date, which beats today.
    Every value is validated or escaped; SQLExecutor2 sends a finished string
    and has no bind parameters, so this function is security-relevant.
    """
    where = []

    date_from = safe_date(date_from)
    date_to = safe_date(date_to)

    if date_from or date_to:
        # A half-open range is a legitimate request: "everything since Monday".
        if date_from:
            where.append("PUNCH_DATE >= TO_DATE('%s')" % date_from)
        if date_to:
            where.append("PUNCH_DATE <= TO_DATE('%s')" % date_to)
        if date_from and date_to and date_from > date_to:
            raise ValueError("the 'from' date is after the 'to' date")
    elif not all_dates:
        where.append("PUNCH_DATE = %s" % today_expr(date_str))

    if q:
        needle = sql_str("%" + q.lower() + "%")
        where.append("(LOWER(FULL_NAME) LIKE %s OR EMPLOYEE_PIN LIKE %s)" % (needle, needle))

    if district:
        where.append("DISTRICT = %s" % sql_str(district))

    return ("WHERE " + " AND ".join(where)) if where else ""


def run_query(sql):
    executor = SQLExecutor2(connection=SNOWFLAKE_CONNECTION)
    df = executor.query_to_df(sql)
    # Timestamps and numpy scalars are not JSON-serialisable as-is.
    return [
        {c: _jsonable(r[c]) for c in df.columns}
        for _, r in df.iterrows()
    ]


def _jsonable(v):
    if v is None:
        return None
    if hasattr(v, "isoformat"):
        return v.isoformat(sep=" ")
    if hasattr(v, "item"):                  # numpy int64/float64/bool_
        try:
            return v.item()
        except Exception:
            pass
    try:
        import pandas as pd
        if pd.isna(v):
            return None
    except Exception:
        pass
    return v


def run_write(statements):
    """Execute DML in one committed transaction.

    The BEGIN/COMMIT is not decoration. query_to_df() opens a connection per
    call and Dataiku's SQL connections are NOT in autocommit, so DML passed as
    the main query is rolled back when the connection closes. On 4 Sep 2026
    that produced a scenario that ran to completion, logged every statement as
    successful, and left every table empty - see dataiku/drain_queue.py, which
    carries the same pattern for the same reason.

    Any write added to this backend must go through here. A bare
    query_to_df("UPDATE ...") will appear to work and change nothing.
    """
    executor = SQLExecutor2(connection=SNOWFLAKE_CONNECTION)
    executor.query_to_df(
        "SELECT 1 AS written",
        pre_queries=["BEGIN"] + list(statements) + ["COMMIT"],
    )


def current_user():
    """Who is using the web app, for the audit columns.

    Dataiku exposes the authenticated user differently across versions, so this
    tries the documented route and falls back rather than throwing - a void
    that cannot be attributed should still be recorded, visibly unattributed.
    Never returns NULL: 'unknown' is a fact, NULL reads like the column was
    never populated.

    VERIFY THIS on your Dataiku version before relying on the audit trail. If
    every void comes back as 'unknown', that is this function, not the user.
    """
    try:
        import dataiku
        info = dataiku.api_client().get_auth_info()
        login = (info or {}).get("authIdentifier")
        if login:
            return str(login)[:128]
    except Exception:
        pass
    for header in ("X-DKU-ProxyUser", "X-Forwarded-User", "X-Remote-User"):
        value = request.headers.get(header)
        if value:
            return str(value)[:128]
    return "unknown"


def fail(message, status=400):
    return jsonify({"ok": False, "error": message}), status


# ═══════════════════════════════════════════════════════════════════════════
#  Routes
# ═══════════════════════════════════════════════════════════════════════════

@app.route("/api/health")
def api_health():
    try:
        run_query("SELECT 1 AS OK")
        return jsonify({"ok": True, "connection": SNOWFLAKE_CONNECTION})
    except Exception as e:
        logger.exception("health check failed")
        return fail("Snowflake connection '%s' failed: %s" % (SNOWFLAKE_CONNECTION, e), 503)


@app.route("/api/dashboard/summary")
def api_summary():
    """Headline counters for the top of the dashboard.

    There is no 'Late' or 'Absent' figure here by design - no shift rules were
    defined, so any such number would be invented. If shift times are agreed
    later, add them to V_DAILY_PRESENCE rather than to this file.
    """
    try:
        date_str = safe_date(request.args.get("date"))
    except ValueError as e:
        return fail(str(e))

    d = today_expr(date_str)

    # Every count here goes through V_EMPLOYEE_ROSTER / V_ATTENDANCE_STREAM
    # rather than the base tables.
    #
    # The base tables now hold tombstones. TOTAL_ENROLLED used to be
    # "FROM EMPLOYEES WHERE STATUS = 'ACTIVE'", which counts deleted staff -
    # STATUS stays 'ACTIVE' on a tombstoned row, because deletion is recorded
    # in DELETED_AT. With 7 rows and 6 deleted that reported 7 enrolled against
    # 1 real person, and NOT_SEEN_TODAY inherited the error: six people who do
    # not work here, listed as not seen.
    #
    # Same for voided punches, which the stream view excludes.
    sql = """
    WITH d AS (SELECT {date_expr} AS REPORT_DATE)
    SELECT
        (SELECT COUNT(*) FROM {s}.V_EMPLOYEE_ROSTER)                      AS TOTAL_ENROLLED,
        (SELECT COUNT(DISTINCT EMPLOYEE_PIN) FROM {s}.V_ATTENDANCE_STREAM
          WHERE PUNCH_DATE = (SELECT REPORT_DATE FROM d))                 AS PRESENT_TODAY,
        (SELECT COUNT(*) FROM {s}.V_ATTENDANCE_STREAM
          WHERE PUNCH_DATE = (SELECT REPORT_DATE FROM d))                 AS PUNCHES_TODAY,
        (SELECT COUNT(*) FROM {s}.V_EMPLOYEE_ROSTER WHERE HAS_BIOMETRICS) AS WITH_BIOMETRICS,
        (SELECT COUNT(*) FROM {s}.V_STAFF_PRESENCE
          WHERE PRESENCE = 'In the Office')                               AS IN_OFFICE_NOW,
        (SELECT COUNT(*) FROM {s}.V_STAFF_PRESENCE
          WHERE PRESENCE = 'Temporarily Out of Office')                   AS STEPPED_OUT_NOW,
        (SELECT COUNT(*) FROM {s}.V_STAFF_PRESENCE
          WHERE PRESENCE = 'Left the Office')                             AS LEFT_TODAY,
        (SELECT MAX(PUNCH_TS) FROM {s}.V_ATTENDANCE_STREAM)               AS LAST_PUNCH_TS,
        -- RECEIVED_AT is UTC (SYSDATE) while PUNCH_TS is device-local Lagos
        -- time. Shown side by side they look an hour apart for no reason, so
        -- this is converted to the device zone to match its neighbour.
        (SELECT CONVERT_TIMEZONE('UTC', '{tz}', MAX(RECEIVED_AT))::TIMESTAMP_NTZ
           FROM {s}.ATTENDANCE_LOGS WHERE VOIDED_AT IS NULL)              AS LAST_SYNC_AT,
        (SELECT REPORT_DATE FROM d)                                       AS REPORT_DATE
    """.format(s=SCHEMA, date_expr=d, tz=DEVICE_TZ)

    try:
        rows = _cached("summary:%s" % (date_str or "today"), CACHE_TTL_SECONDS,
                       lambda: run_query(sql))
    except Exception as e:
        logger.exception("summary query failed")
        return fail(str(e), 500)

    row = rows[0] if rows else {}
    enrolled = row.get("TOTAL_ENROLLED") or 0
    present = row.get("PRESENT_TODAY") or 0

    return jsonify({
        "ok": True,
        "report_date":      row.get("REPORT_DATE"),
        "total_enrolled":   enrolled,
        "present_today":    present,
        "punches_today":    row.get("PUNCHES_TODAY") or 0,
        "with_biometrics":  row.get("WITH_BIOMETRICS") or 0,
        "not_seen_today":   max(enrolled - present, 0),
        # Live occupancy, from V_STAFF_PRESENCE. "In the office" counts only
        # people whose most recent punch today was Clock In or Back on Seat;
        # somebody who stepped out is counted separately rather than as absent.
        "in_office_now":    row.get("IN_OFFICE_NOW") or 0,
        "stepped_out_now":  row.get("STEPPED_OUT_NOW") or 0,
        "left_today":       row.get("LEFT_TODAY") or 0,
        "last_punch_ts":    row.get("LAST_PUNCH_TS"),
        "last_sync_at":     row.get("LAST_SYNC_AT"),
    })


@app.route("/api/attendance/logs")
def api_logs():
    """Live punch stream, newest first."""
    try:
        date_str = safe_date(request.args.get("date"))
    except ValueError as e:
        return fail(str(e))

    limit = safe_limit(request.args.get("limit"), 100)
    q = (request.args.get("q") or "").strip()
    all_dates = request.args.get("all") == "1"
    district = (request.args.get("district") or "").strip()

    try:
        where = logs_where(date_str, request.args.get("from"), request.args.get("to"),
                           q, district, all_dates)
    except ValueError as e:
        return fail(str(e))

    sql = """
    SELECT LOG_ID, EMPLOYEE_PIN, FULL_NAME, DEPARTMENT,
           PUNCH_TS, PUNCH_TIME, PUNCH_TYPE, PUNCH_NAME,
           VERIFY_LABEL, PUNCH_SEQ, DISTRICT, DEVICE_SN
    FROM {s}.V_ATTENDANCE_STREAM
    {where}
    ORDER BY PUNCH_TS DESC
    LIMIT {limit}
    """.format(s=SCHEMA, where=where, limit=limit)

    try:
        rows = _cached("logs:%s" % (sql,), CACHE_TTL_SECONDS, lambda: run_query(sql))
    except Exception as e:
        logger.exception("logs query failed")
        return fail(str(e), 500)

    return jsonify({
        "ok": True,
        "count": len(rows),
        "rows": [{
            "log_id":      r.get("LOG_ID"),
            "pin":         r.get("EMPLOYEE_PIN"),
            "name":        r.get("FULL_NAME"),
            "department":  r.get("DEPARTMENT"),
            "timestamp":   r.get("PUNCH_TS"),
            "time":        r.get("PUNCH_TIME"),
            "verify":      r.get("VERIFY_LABEL"),
            # The real punch type, resolved through PUNCH_TYPES.
            #
            # This used to be inferred from PUNCH_SEQ, because on stock Horus
            # firmware the State field reads 0 in both directions and the first
            # punch of the day was the only reliable arrival signal. That is no
            # longer true: our app sends genuine ADMS codes, so a Clock Out is
            # recorded as a Clock Out. Inferring from row order would now
            # display it as "Subsequent" while the database knows better.
            "punch_type":  r.get("PUNCH_TYPE"),
            "punch_name":  r.get("PUNCH_NAME"),
            "sequence":    r.get("PUNCH_SEQ"),
            "district":    r.get("DISTRICT"),
            "device_sn":   r.get("DEVICE_SN"),
        } for r in rows],
    })


@app.route("/api/employees")
def api_employees():
    """Roster with biometric enrolment status."""
    limit = safe_limit(request.args.get("limit"), 500)
    q = (request.args.get("q") or "").strip()

    where = ""
    if q:
        needle = sql_str("%" + q.lower() + "%")
        where = ("WHERE LOWER(FULL_NAME) LIKE {n} OR DEVICE_PIN LIKE {n} "
                 "OR LOWER(COALESCE(DEPARTMENT,'')) LIKE {n}").format(n=needle)

    sql = """
    SELECT DEVICE_PIN, FULL_NAME, DEPARTMENT, STATUS, DEVICE_SN,
           TEMPLATE_COUNT, HAS_BIOMETRICS, LAST_PUNCH_TS, CREATED_AT
    FROM {s}.V_EMPLOYEE_ROSTER
    {where}
    ORDER BY HAS_BIOMETRICS ASC, FULL_NAME ASC
    LIMIT {limit}
    """.format(s=SCHEMA, where=where, limit=limit)

    try:
        rows = _cached("emp:%s:%s" % (q, limit), CACHE_TTL_SECONDS,
                       lambda: run_query(sql))
    except Exception as e:
        logger.exception("employees query failed")
        return fail(str(e), 500)

    return jsonify({
        "ok": True,
        "count": len(rows),
        "rows": [{
            "pin":             r.get("DEVICE_PIN"),
            "name":            r.get("FULL_NAME"),
            "department":      r.get("DEPARTMENT"),
            "status":          r.get("STATUS"),
            "device_sn":       r.get("DEVICE_SN"),
            "template_count":  r.get("TEMPLATE_COUNT") or 0,
            "has_biometrics":  bool(r.get("HAS_BIOMETRICS")),
            "last_punch_ts":   r.get("LAST_PUNCH_TS"),
            "created_at":      r.get("CREATED_AT"),
        } for r in rows],
    })


@app.route("/api/districts")
def api_districts():
    """The sites that appear in attendance data.

    Read from punches rather than from a lookup table, because there isn't one:
    a district is stamped onto a punch by the terminal that recorded it, from
    the Location set on that device. So this list is "sites that have ever
    recorded a punch", which is the only definition the data supports.
    """
    sql = """
    SELECT DISTINCT DISTRICT
    FROM {s}.V_ATTENDANCE_STREAM
    WHERE DISTRICT IS NOT NULL AND TRIM(DISTRICT) <> ''
    ORDER BY DISTRICT
    """.format(s=SCHEMA)
    try:
        rows = _cached("districts", 300, lambda: run_query(sql))
    except Exception as e:
        logger.exception("districts query failed")
        return fail(str(e), 500)
    return jsonify({"ok": True, "districts": [r.get("DISTRICT") for r in rows]})


@app.route("/api/attendance/export")
def api_export():
    """Attendance as CSV, honouring the same filters as the table.

    Uses logs_where() so the export and the screen can never disagree - an
    export that quietly differs from what an admin was looking at is worse than
    having no export at all.

    No LIMIT. An export capped at 100 rows that says nothing about being capped
    is a silent data-loss bug in a file somebody will treat as a record; the
    row cap belongs on the screen, not on the download.
    """
    q = (request.args.get("q") or "").strip()
    district = (request.args.get("district") or "").strip()
    all_dates = request.args.get("all") == "1"

    try:
        date_str = safe_date(request.args.get("date"))
        where = logs_where(date_str, request.args.get("from"), request.args.get("to"),
                           q, district, all_dates)
    except ValueError as e:
        return fail(str(e))

    sql = """
    SELECT PUNCH_TS, PUNCH_DATE, PUNCH_TIME, EMPLOYEE_PIN AS SF_ID, FULL_NAME,
           DEPARTMENT, DISTRICT, PUNCH_NAME, VERIFY_LABEL, DEVICE_SN, LOG_ID
    FROM {s}.V_ATTENDANCE_STREAM
    {where}
    ORDER BY PUNCH_TS DESC
    """.format(s=SCHEMA, where=where)

    try:
        rows = run_query(sql)
    except Exception as e:
        logger.exception("export query failed")
        return fail(str(e), 500)

    columns = ["PUNCH_DATE", "PUNCH_TIME", "SF_ID", "FULL_NAME", "DEPARTMENT",
               "DISTRICT", "PUNCH_NAME", "VERIFY_LABEL", "DEVICE_SN", "LOG_ID"]

    def csv_cell(value):
        """Quote for CSV, and defuse spreadsheet formula injection.

        A name beginning = + - or @ is executed as a formula by Excel and
        Sheets on open. Staff names are typed on a terminal by whoever has
        enrolment rights, so this is reachable input, and the file is destined
        for exactly those applications.
        """
        if value is None:
            return ""
        s = str(value)
        if s[:1] in ("=", "+", "-", "@", "\t", "\r"):
            s = "'" + s
        return '"' + s.replace('"', '""') + '"'

    lines = [",".join(columns)]
    for r in rows:
        lines.append(",".join(csv_cell(r.get(c)) for c in columns))
    body = "\r\n".join(lines) + "\r\n"

    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    scope = district.replace(" ", "-") if district else "all"
    filename = "attendance-%s-%s.csv" % (scope, stamp)

    return app.response_class(
        # BOM so Excel opens UTF-8 correctly instead of mangling accented names.
        response=u"﻿" + body,
        mimetype="text/csv; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="%s"' % filename},
    )


@app.route("/api/presence")
def api_presence():
    """Who is in the office, optionally filtered by a search term.

    The four states come from V_STAFF_PRESENCE and are defined there. Note what
    is NOT here: no Late, no Absent. No shift rules exist, so those would be
    invented. "Not in Today" is a fact; "Absent" is a judgement.
    """
    q = (request.args.get("q") or "").strip().lower()
    district = (request.args.get("district") or "").strip()
    limit = safe_limit(request.args.get("limit"), 200)

    clauses = []
    if q:
        needle = sql_str("%" + q + "%")
        clauses.append(("(LOWER(FULL_NAME) LIKE {n} OR SF_ID LIKE {n} "
                        "OR LOWER(COALESCE(DEPARTMENT,'')) LIKE {n})").format(n=needle))
    if district:
        # DISTRICT here is where the person last punched TODAY, so filtering by
        # it necessarily excludes "Not in Today" - those people have no punch
        # and therefore no site. The UI says so; without that note a district
        # filter looks like it is losing staff.
        clauses.append("DISTRICT = %s" % sql_str(district))
    where = ("WHERE " + " AND ".join(clauses)) if clauses else ""

    sql = """
    SELECT SF_ID, DEVICE_PIN, FIRST_NAME, LAST_NAME, FULL_NAME, DEPARTMENT,
           GENDER, PRESENCE, LAST_PUNCH_NAME, LAST_PUNCH_TIME, LAST_PUNCH_TS,
           DISTRICT, TEMPLATE_COUNT
    FROM {s}.V_STAFF_PRESENCE
    {where}
    ORDER BY
        CASE PRESENCE
            WHEN 'In the Office'             THEN 1
            WHEN 'Temporarily Out of Office' THEN 2
            WHEN 'Left the Office'           THEN 3
            ELSE 4
        END,
        FULL_NAME
    LIMIT {limit}
    """.format(s=SCHEMA, where=where, limit=limit)

    try:
        rows = _cached("presence:%s:%s:%s" % (q, district, limit), CACHE_TTL_SECONDS,
                       lambda: run_query(sql))
    except Exception as e:
        logger.exception("presence query failed")
        return fail(str(e), 500)

    totals = {}
    for r in rows:
        key = r.get("PRESENCE") or "Unknown"
        totals[key] = totals.get(key, 0) + 1

    return jsonify({
        "ok": True,
        "count": len(rows),
        # Totals cover the returned rows, so with a search term they describe
        # the matches, not the whole organisation. The dashboard cards use
        # /api/dashboard/summary for the org-wide figure.
        "totals": totals,
        "rows": [{
            "sf_id":        r.get("SF_ID"),
            "pin":          r.get("DEVICE_PIN"),
            "first_name":   r.get("FIRST_NAME"),
            "last_name":    r.get("LAST_NAME"),
            "name":         r.get("FULL_NAME"),
            "department":   r.get("DEPARTMENT"),
            "gender":       r.get("GENDER"),
            "presence":     r.get("PRESENCE"),
            "last_punch":   r.get("LAST_PUNCH_NAME"),
            "last_time":    r.get("LAST_PUNCH_TIME"),
            "last_ts":      r.get("LAST_PUNCH_TS"),
            "district":     r.get("DISTRICT"),
            "templates":    r.get("TEMPLATE_COUNT") or 0,
        } for r in rows],
    })


@app.route("/api/employees/<pin>", methods=["PUT", "POST"])
def api_employee_update(pin):
    """Edit a staff member's details.

    Deliberately narrow. Four fields, and DEVICE_PIN is not one of them: it is
    the SF ID, every punch ever recorded is attributed to it, and changing it
    would silently reassign somebody's entire attendance history.

    UPDATED_AT must move. It is what publish_roster.py turns into the roster
    version, and a device only applies rows newer than its watermark - so an
    edit that does not bump it reaches Snowflake, looks correct on every
    dashboard, and never arrives on a single terminal. The office would see the
    new name while the door kept using the old one.
    """
    payload = request.get_json(silent=True) or {}

    first = (payload.get("first_name") or "").strip()
    last = (payload.get("last_name") or "").strip()
    department = (payload.get("department") or "").strip()
    gender = (payload.get("gender") or "").strip()

    if not first or not last:
        return fail("first_name and last_name are both required")
    if gender and gender not in ("Male", "Female"):
        return fail("gender must be Male or Female")

    pin = str(pin).strip()
    if not pin or len(pin) > 32:
        return fail("invalid SF ID")

    # Refuse to edit somebody who has been deleted. Their tombstone is on its
    # way to every terminal; editing them would push a contradictory update
    # behind it and the outcome would depend on which arrived last.
    check = run_query(
        "SELECT COUNT(*) AS N FROM {s}.EMPLOYEES "
        "WHERE DEVICE_PIN = {pin} AND DELETED_AT IS NULL".format(
            s=SCHEMA, pin=sql_str(pin))
    )
    if not check or (check[0].get("N") or 0) == 0:
        return fail("no active staff member with SF ID %s" % pin, 404)

    statement = """
    UPDATE {s}.EMPLOYEES SET
        FIRST_NAME = {first},
        LAST_NAME  = {last},
        FULL_NAME  = {full},
        DEPARTMENT = {dept},
        GENDER     = {gender},
        UPDATED_AT = SYSDATE()
    WHERE DEVICE_PIN = {pin} AND DELETED_AT IS NULL
    """.format(
        s=SCHEMA,
        first=sql_str(first), last=sql_str(last),
        full=sql_str((first + " " + last).strip()),
        dept=sql_str(department) if department else "NULL",
        gender=sql_str(gender) if gender else "NULL",
        pin=sql_str(pin),
    )

    try:
        run_write([statement])
    except Exception as e:
        logger.exception("employee update failed")
        return fail(str(e), 500)

    with _cache_lock:
        _cache.clear()

    return jsonify({
        "ok": True,
        "pin": pin,
        "name": (first + " " + last).strip(),
        # Set expectations: this is not instant on the terminals.
        "note": ("Saved. Terminals pick this up on the next roster sync - "
                 "about two scenario cycles, so up to ten minutes."),
    })


@app.route("/api/attendance/<log_id>/void", methods=["POST"])
def api_void_punch(log_id):
    """Void a punch. Not a delete.

    The row stays, marked with who voided it and when, and disappears from
    every view and count. Attendance feeds pay and settles disputes, so "why is
    that punch missing" has to have an answer - and a mistake here has to be
    reversible, which a DELETE is not.
    """
    payload = request.get_json(silent=True) or {}
    reason = (payload.get("reason") or "").strip()
    if not reason:
        return fail("a reason is required - it is the whole point of the audit trail")

    log_id = str(log_id).strip()
    if not log_id or len(log_id) > 160:
        return fail("invalid log id")

    existing = run_query(
        "SELECT VOIDED_AT FROM {s}.ATTENDANCE_LOGS WHERE LOG_ID = {lid}".format(
            s=SCHEMA, lid=sql_str(log_id, 160))
    )
    if not existing:
        return fail("no punch with that id", 404)
    if existing[0].get("VOIDED_AT"):
        return fail("that punch is already voided", 409)

    statement = """
    UPDATE {s}.ATTENDANCE_LOGS SET
        VOIDED_AT   = SYSDATE(),
        VOIDED_BY   = {who},
        VOID_REASON = {reason}
    WHERE LOG_ID = {lid} AND VOIDED_AT IS NULL
    """.format(
        s=SCHEMA,
        who=sql_str(current_user()),
        reason=sql_str(reason, 500),
        lid=sql_str(log_id, 160),
    )

    try:
        run_write([statement])
    except Exception as e:
        logger.exception("void failed")
        return fail(str(e), 500)

    with _cache_lock:
        _cache.clear()

    return jsonify({"ok": True, "log_id": log_id, "voided_by": current_user()})


@app.route("/api/attendance/<log_id>/restore", methods=["POST"])
def api_restore_punch(log_id):
    """Undo a void. The reason a void is a write rather than a delete."""
    log_id = str(log_id).strip()
    if not log_id or len(log_id) > 160:
        return fail("invalid log id")

    statement = """
    UPDATE {s}.ATTENDANCE_LOGS SET
        VOIDED_AT = NULL, VOIDED_BY = NULL, VOID_REASON = NULL
    WHERE LOG_ID = {lid}
    """.format(s=SCHEMA, lid=sql_str(log_id, 160))

    try:
        run_write([statement])
    except Exception as e:
        logger.exception("restore failed")
        return fail(str(e), 500)

    with _cache_lock:
        _cache.clear()

    return jsonify({"ok": True, "log_id": log_id})


@app.route("/api/devices")
def api_devices():
    """Terminal liveness. A Horus H1 with Realtime=1 and Delay=10 should be
    seen constantly; a long gap means the link, not the staff, is the problem.
    """
    sql = """
    SELECT DEVICE_SN, LAST_SEEN_AT, SECONDS_SINCE_SEEN, TOTAL_PUNCHES
    FROM {s}.V_DEVICE_HEALTH
    ORDER BY LAST_SEEN_AT DESC
    """.format(s=SCHEMA)
    try:
        rows = _cached("devices", CACHE_TTL_SECONDS, lambda: run_query(sql))
    except Exception as e:
        logger.exception("devices query failed")
        return fail(str(e), 500)

    return jsonify({
        "ok": True,
        "rows": [{
            "device_sn":     r.get("DEVICE_SN"),
            "last_seen_at":  r.get("LAST_SEEN_AT"),
            "seconds_since": r.get("SECONDS_SINCE_SEEN"),
            "total_punches": r.get("TOTAL_PUNCHES"),
            "online":        (r.get("SECONDS_SINCE_SEEN") or 999999) < 900,
        } for r in rows],
    })
