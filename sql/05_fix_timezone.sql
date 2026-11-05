-- ============================================================================
-- Move every system timestamp to UTC.
--
-- CONFIRMED on 5 Sep 2026: HOURS_OFF = 8 from both Snowsight and Dataiku, so
-- the session timezone is Snowflake's default, America/Los_Angeles. Every
-- CURRENT_TIMESTAMP() write has been landing in US Pacific while PUNCH_TS
-- arrives from the device in Lagos time.
--
-- Two reasons this is worth fixing rather than documenting:
--
--   1. US Pacific observes daylight saving and Lagos does not, so the skew is
--      not even constant - it becomes 7 hours in November. Any comparison
--      between a punch and its RECEIVED_AT shifts by an hour, twice a year,
--      for no reason visible in the data.
--   2. The session timezone is a per-client setting. Two writers can disagree,
--      and nothing records which one wrote a given row.
--
-- The fix is SYSDATE(), which returns UTC as TIMESTAMP_NTZ regardless of any
-- session parameter. PUNCH_TS is deliberately NOT touched: it is the device's
-- wall clock, it is what staff and payroll mean by "what time did they arrive",
-- and rewriting it would change the business record.
--
-- Run sections 1-3 in order. Section 4 is optional and must run EXACTLY ONCE.
-- ============================================================================

USE DATABASE DEVELOPMENT;
USE SCHEMA   ATTENDANCE;


-- ---------------------------------------------------------------------------
-- 1. New rows land in UTC - handled in the parser, NOT here.
--
-- There is nothing to run in this section. The first version of this file tried
--     ALTER TABLE ... ALTER COLUMN ... SET DEFAULT SYSDATE();
-- which Snowflake rejects: "Unsupported feature 'Alter Column Set Default'".
-- Snowflake only lets you point a default at a sequence, or drop it. An
-- existing column's default expression cannot be changed at all.
--
-- The fix is better than the one that failed. Those defaults only ever fired
-- because the MERGE statements omitted the columns, so parse-adms-request.js
-- now writes every one of them explicitly with SYSDATE():
--
--   RAW_PUSH_LOG         RECEIVED_AT
--   ATTENDANCE_LOGS      RECEIVED_AT
--   EMPLOYEES            CREATED_AT, UPDATED_AT   (both inserts)
--   DEPARTMENTS          CREATED_AT, UPDATED_AT
--   BIOMETRIC_TEMPLATES  UPDATED_AT
--
-- Explicit beats implicit here anyway: the value no longer depends on a table
-- default that cannot be inspected from the code doing the writing.
--
-- The stale Pacific defaults remain on the tables and are now unreachable from
-- the pipeline. The one caveat: any OTHER writer that omits these columns still
-- gets Pacific. Section 1 of 03_upgrade_to_v2.sql does exactly that in its
-- DEPARTMENTS seed - harmless, since it runs once - but keep it in mind before
-- adding a new writer. A fresh install from 01_snowflake_schema.sql has
-- SYSDATE() defaults and no such trap.


-- ---------------------------------------------------------------------------
-- 2. Re-run 02_dataiku_views.sql
--
-- DEVICE_TODAY() and V_DEVICE_HEALTH both used CURRENT_TIMESTAMP(). The first
-- claimed its input was UTC when it was Pacific, so "today" was wrong by hours
-- and punches near midnight landed on the wrong day. Both now use SYSDATE().
-- ---------------------------------------------------------------------------
-- (execute sql/02_dataiku_views.sql)


-- ---------------------------------------------------------------------------
-- 3. Paste the updated parser into the n8n Code node
--
-- parse-adms-request.js writes UPDATED_AT and DELETED_AT on every roster push.
-- Until it is updated it keeps writing Pacific, and section 4 below will have
-- to be repeated. Do this BEFORE the backfill.
-- ---------------------------------------------------------------------------


-- ---------------------------------------------------------------------------
-- 4. OPTIONAL BACKFILL - RUN EXACTLY ONCE
--
-- Shifts timestamps already stored in Pacific into UTC. It is NOT idempotent:
-- running it twice shifts twice and there is nothing in the data to detect
-- that. If you are unsure whether it has run, do not guess - check query 4 of
-- 04_check_timezone.sql first. MINUTES_APART near 0 means it has already been
-- applied (or never needed to be).
--
-- If these tables still hold only test rows, deleting them is simpler and
-- safer than converting them.
--
-- Note this MOVES ROSTER VERSIONS FORWARD, because UPDATED_AT is what
-- publish_roster.py turns into `version`. That direction is safe: devices see
-- rows as newer and re-apply them, which is idempotent. A backwards shift would
-- have been the dangerous one - devices would never see those rows again.
-- ---------------------------------------------------------------------------

-- Look before you leap. Expect roughly an 8-hour gap in the 'before' column.
SELECT 'before' AS STAGE, DEVICE_PIN, UPDATED_AT,
       CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', UPDATED_AT) AS WOULD_BECOME
FROM   EMPLOYEES
ORDER  BY UPDATED_AT DESC
LIMIT  5;

/*  Uncomment and run once.

UPDATE EMPLOYEES SET
    CREATED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', CREATED_AT),
    UPDATED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', UPDATED_AT),
    DELETED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', DELETED_AT);

UPDATE DEPARTMENTS SET
    CREATED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', CREATED_AT),
    UPDATED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', UPDATED_AT),
    DELETED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', DELETED_AT);

UPDATE BIOMETRIC_TEMPLATES SET
    UPDATED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', UPDATED_AT);

-- PUNCH_TS is NOT in this list, deliberately. It is the device's local wall
-- clock and the business record of when somebody arrived.
UPDATE ATTENDANCE_LOGS SET
    RECEIVED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', RECEIVED_AT);

UPDATE RAW_PUSH_LOG SET
    RECEIVED_AT = CONVERT_TIMEZONE('America/Los_Angeles', 'UTC', RECEIVED_AT);

*/


-- ---------------------------------------------------------------------------
-- 5. Verify
--
-- HOURS_OFF should be 1: stored timestamps are UTC and Lagos is UTC+1.
-- MINUTES_APART on a punch taken AFTER this fix should be between 0 and about
-- 10 - the device syncs immediately and the drain runs every five minutes -
-- offset by the +1 hour, so expect roughly 60.
-- ---------------------------------------------------------------------------
SELECT
    SYSDATE()                                                            AS NOW_UTC,
    CONVERT_TIMEZONE('UTC', 'Africa/Lagos', SYSDATE())::TIMESTAMP_NTZ    AS NOW_LAGOS,
    DEVICE_TODAY()                                                       AS DEVICE_TODAY,
    CONVERT_TIMEZONE('UTC', 'Africa/Lagos', SYSDATE())::DATE             AS ACTUAL_LAGOS_DATE;
