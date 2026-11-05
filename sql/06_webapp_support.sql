-- ============================================================================
-- Support for the admin web app: voiding punches, and editing staff.
--
-- Run this once, then re-run 02_dataiku_views.sql.
--
-- This is the first time anything other than the device writes to these
-- tables. Until now the flow was one-directional - terminal, n8n, Dataiku,
-- Snowflake - and every row could be traced to a punch or an enrolment. The
-- web app breaks that, so both new capabilities are built to leave a trail.
-- ============================================================================

USE DATABASE DEVELOPMENT;
USE SCHEMA   ATTENDANCE;


-- ---------------------------------------------------------------------------
-- 1. Voiding a punch
--
-- A void is a write, not a delete. Attendance feeds pay and settles disputes;
-- "why is that punch missing" needs an answer, and DELETE leaves none. It is
-- also reversible, which matters the first time somebody voids the wrong row.
--
-- VOIDED_BY records the Dataiku user who did it. If the web app cannot
-- determine the user it writes 'unknown' rather than NULL, so an unattributed
-- void is visibly unattributed instead of looking like it never happened.
-- ---------------------------------------------------------------------------
ALTER TABLE ATTENDANCE_LOGS ADD COLUMN IF NOT EXISTS VOIDED_AT     TIMESTAMP_NTZ;
ALTER TABLE ATTENDANCE_LOGS ADD COLUMN IF NOT EXISTS VOIDED_BY     VARCHAR(128);
ALTER TABLE ATTENDANCE_LOGS ADD COLUMN IF NOT EXISTS VOID_REASON   VARCHAR(500);

-- Voided rows are excluded by V_ATTENDANCE_STREAM and therefore by everything
-- built on it. To review what has been voided, query the base table.
CREATE OR REPLACE VIEW V_VOIDED_PUNCHES AS
SELECT
    l.LOG_ID,
    l.EMPLOYEE_PIN,
    COALESCE(
        NULLIF(TRIM(COALESCE(e.FIRST_NAME, '') || ' ' || COALESCE(e.LAST_NAME, '')), ''),
        NULLIF(TRIM(e.FULL_NAME), ''),
        'PIN ' || l.EMPLOYEE_PIN
    )                AS FULL_NAME,
    l.PUNCH_TS,
    pt.PUNCH_NAME,
    l.LOCATION,
    l.DEVICE_SN,
    l.VOIDED_AT,
    l.VOIDED_BY,
    l.VOID_REASON
FROM ATTENDANCE_LOGS l
LEFT JOIN EMPLOYEES e    ON e.DEVICE_PIN = l.EMPLOYEE_PIN
LEFT JOIN PUNCH_TYPES pt ON pt.CODE      = l.PUNCH_TYPE
WHERE l.VOIDED_AT IS NOT NULL;


-- ---------------------------------------------------------------------------
-- 2. Editing staff from the web app
--
-- No schema change needed - but there is one rule the web app must follow, and
-- it is not enforceable here, so it is written down.
--
-- An edit MUST set UPDATED_AT = SYSDATE().
--
-- UPDATED_AT is what publish_roster.py turns into the roster `version`, and a
-- device only applies rows whose version exceeds its watermark. An edit that
-- does not move UPDATED_AT reaches Snowflake, shows correctly in every
-- dashboard, and never arrives on a single terminal - so the office sees the
-- new name and the door still greets them by the old one.
--
-- DEVICE_PIN must never be edited. It is the SF ID, and every punch ever
-- recorded is attributed to it; changing it silently reassigns that person's
-- entire attendance history to somebody who may not exist.
--
-- Round trip, once an edit lands:
--   web app -> EMPLOYEES.UPDATED_AT bumped
--           -> publish_roster.py picks it up on the next scenario run
--           -> zk_roster
--           -> device pulls it, and RosterSync applies it unless that device
--              has an unpushed local edit of its own
-- Roughly two scenario cycles, so up to about ten minutes.
-- ---------------------------------------------------------------------------


-- ---------------------------------------------------------------------------
-- 3. Re-run the views
-- ---------------------------------------------------------------------------
-- (execute sql/02_dataiku_views.sql - it adds V_STAFF_PRESENCE and makes
--  V_ATTENDANCE_STREAM exclude voided punches)


-- ---------------------------------------------------------------------------
-- 4. Check
-- ---------------------------------------------------------------------------
SELECT PRESENCE, COUNT(*) AS STAFF
FROM   V_STAFF_PRESENCE
GROUP  BY PRESENCE
ORDER  BY STAFF DESC;
