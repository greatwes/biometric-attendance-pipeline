-- ============================================================================
-- Which timezone is actually being written into this schema?
--
-- Read-only. Nothing here changes data or settings.
--
-- WHY: punches arrive from the device as naive local time (WAT), while
-- CREATED_AT / UPDATED_AT / DELETED_AT / RECEIVED_AT are written by
-- CURRENT_TIMESTAMP(), which uses whatever the SESSION timezone happens to be.
-- Snowflake's default is America/Los_Angeles. If nobody has changed it, half
-- this schema is in Lagos time and half is in Pacific time, and nothing
-- anywhere flags the difference.
--
-- Observed on 5 Sep 2026: a punch taken at 14:47 WAT produced an UPDATED_AT of
-- 06:51 - seven hours behind UTC, which is Pacific. Query 4 is what settles it,
-- because it measures the skew on real rows rather than inferring it.
-- ============================================================================

USE DATABASE DEVELOPMENT;
USE SCHEMA   ATTENDANCE;


-- ---------------------------------------------------------------------------
-- 1. The setting itself.
--
-- Look at BOTH columns that matter:
--   value - the timezone in force
--   level - blank means it is only the account default and ANY client can set
--           its own, so Dataiku may be writing in a different zone from your
--           worksheet. ACCOUNT or USER means it is pinned.
-- ---------------------------------------------------------------------------
SHOW PARAMETERS LIKE 'TIMEZONE';


-- ---------------------------------------------------------------------------
-- 2. What a write would store right now, versus what Lagos time actually is.
--
-- HOURS_OFF is the whole answer:
--   0  - stored timestamps are already Lagos time, nothing to fix
--   8  - stored timestamps are Pacific (or 7, depending on daylight saving)
--   1  - stored timestamps are UTC
-- ---------------------------------------------------------------------------
SELECT
    CURRENT_TIMESTAMP()                                                   AS NOW_SESSION,
    CURRENT_TIMESTAMP()::TIMESTAMP_NTZ                                    AS NOW_AS_STORED,
    CONVERT_TIMEZONE('Africa/Lagos', CURRENT_TIMESTAMP())::TIMESTAMP_NTZ  AS NOW_IN_LAGOS,
    DATEDIFF(
        'hour',
        CURRENT_TIMESTAMP()::TIMESTAMP_NTZ,
        CONVERT_TIMEZONE('Africa/Lagos', CURRENT_TIMESTAMP())::TIMESTAMP_NTZ
    )                                                                     AS HOURS_OFF;


-- ---------------------------------------------------------------------------
-- 3. Is DEVICE_TODAY() returning the right day?
--
-- It does CONVERT_TIMEZONE('UTC', 'Africa/Lagos', CURRENT_TIMESTAMP()), which
-- ASSERTS the input is UTC. If the session is Pacific that assertion is false
-- and the result is hours out - which only becomes visible near midnight, when
-- punches land on the wrong day in every dashboard that groups by date.
--
-- DEVICE_TODAY and ACTUAL_LAGOS_DATE must match.
-- ---------------------------------------------------------------------------
SELECT
    DEVICE_TODAY()                                              AS DEVICE_TODAY,
    CONVERT_TIMEZONE('Africa/Lagos', CURRENT_TIMESTAMP())::DATE AS ACTUAL_LAGOS_DATE,
    CURRENT_DATE()                                              AS SESSION_DATE;


-- ---------------------------------------------------------------------------
-- 4. The one that proves it, on real rows.
--
-- A punch reaches Snowflake within seconds of being taken - the device fires a
-- sync immediately and the Dataiku drain runs every five minutes. So
-- MINUTES_APART should be somewhere between 0 and about 10.
--
-- A value near -480 (or -420) means PUNCH_TS is Lagos and RECEIVED_AT is
-- Pacific: the eight-hour skew, measured rather than argued.
-- ---------------------------------------------------------------------------
SELECT
    LOG_ID,
    PUNCH_TS,                                   -- device local, WAT
    RECEIVED_AT,                                -- CURRENT_TIMESTAMP(), session tz
    DATEDIFF('minute', PUNCH_TS, RECEIVED_AT) AS MINUTES_APART
FROM ATTENDANCE_LOGS
ORDER BY RECEIVED_AT DESC
LIMIT 5;
