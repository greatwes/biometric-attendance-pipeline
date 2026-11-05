-- ============================================================================
-- Reporting views. The Dataiku web app backend reads these, not the base
-- tables, so that display logic lives in Snowflake where it is easy to change
-- without redeploying the web app.
-- ============================================================================

USE SCHEMA DEVELOPMENT.ATTENDANCE;

-- Single place to define "what timezone is the device in". The device sends
-- naive local time, so this is only used to answer "what is today?" - it does
-- not shift any stored value.
--   Nigeria (WAT)  = 'Africa/Lagos'   UTC+1
--   Kenya  (EAT)   = 'Africa/Nairobi' UTC+3
--   Rwanda (CAT)   = 'Africa/Kigali'  UTC+2
-- SYSDATE(), not CURRENT_TIMESTAMP(). This function asserts its input is UTC,
-- and CURRENT_TIMESTAMP() is in the SESSION timezone - which on this account is
-- the Snowflake default, US Pacific. The assertion was simply false, and
-- "today" came out up to eight hours wrong, putting punches taken near midnight
-- on the wrong day in every view that groups by date.
--
-- SYSDATE() returns UTC as TIMESTAMP_NTZ regardless of session settings, so the
-- conversion below is now true by construction rather than by hoping nobody
-- changes a parameter. It is also immune to US daylight saving, which would
-- otherwise have shifted this by an hour twice a year.
CREATE OR REPLACE FUNCTION DEVICE_TODAY()
RETURNS DATE
AS
$$
    CONVERT_TIMEZONE('UTC', 'Africa/Lagos', SYSDATE())::DATE
$$;


-- ---------------------------------------------------------------------------
-- v_employee_roster - enrolment status per PIN
--
-- LEFT JOIN, not INNER: a punch can arrive for a PIN whose USERINFO record was
-- never pushed (common when staff were enrolled before the terminal was pointed
-- at this server). Those people must still appear, flagged as unnamed.
--
-- Deleted staff are excluded. DELETED_AT is a sync tombstone, not a business
-- record: the row must stay in EMPLOYEES so the deletion can propagate to every
-- terminal, but a removed person is not part of the roster and must not be
-- counted in headcount. Their attendance history is untouched - see
-- V_ATTENDANCE_STREAM, which still resolves their name.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_EMPLOYEE_ROSTER AS
SELECT
    e.DEVICE_PIN,
    e.DEVICE_PIN                                                   AS SF_ID,
    e.FIRST_NAME,
    e.LAST_NAME,
    -- Prefer the split parts when we have them, fall back to whatever the
    -- device sent as a single string, and only then to the PIN. A stock ZK
    -- terminal sends only Name=, so both spellings coexist indefinitely.
    COALESCE(
        NULLIF(TRIM(COALESCE(e.FIRST_NAME, '') || ' ' || COALESCE(e.LAST_NAME, '')), ''),
        NULLIF(TRIM(e.FULL_NAME), ''),
        'PIN ' || e.DEVICE_PIN
    )                                                              AS FULL_NAME,
    e.DEPARTMENT,
    e.GENDER,
    e.STATUS,
    e.DEVICE_SN,
    e.CREATED_AT,
    e.UPDATED_AT,
    COALESCE(b.TEMPLATE_COUNT, 0)                       AS TEMPLATE_COUNT,
    IFF(COALESCE(b.TEMPLATE_COUNT, 0) > 0, TRUE, FALSE) AS HAS_BIOMETRICS,
    p.LAST_PUNCH_TS
FROM EMPLOYEES e
LEFT JOIN (
    SELECT EMPLOYEE_PIN, COUNT(*) AS TEMPLATE_COUNT
    FROM BIOMETRIC_TEMPLATES
    WHERE IS_VALID
    GROUP BY EMPLOYEE_PIN
) b ON b.EMPLOYEE_PIN = e.DEVICE_PIN
LEFT JOIN (
    SELECT EMPLOYEE_PIN, MAX(PUNCH_TS) AS LAST_PUNCH_TS
    FROM ATTENDANCE_LOGS
    GROUP BY EMPLOYEE_PIN
) p ON p.EMPLOYEE_PIN = e.DEVICE_PIN
WHERE e.DELETED_AT IS NULL;


-- ---------------------------------------------------------------------------
-- v_attendance_stream - punches with names resolved, newest first
--
-- VERIFY_MODE decode covers the modes the Horus H1 can actually produce.
-- Anything else is surfaced as "Mode <n>" rather than being hidden, so an
-- unexpected value shows up in the UI instead of silently becoming NULL.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_ATTENDANCE_STREAM AS
SELECT
    l.LOG_ID,
    l.EMPLOYEE_PIN,
    l.EMPLOYEE_PIN                               AS SF_ID,
    e.FIRST_NAME,
    e.LAST_NAME,
    COALESCE(
        NULLIF(TRIM(COALESCE(e.FIRST_NAME, '') || ' ' || COALESCE(e.LAST_NAME, '')), ''),
        NULLIF(TRIM(e.FULL_NAME), ''),
        'Unknown (PIN ' || l.EMPLOYEE_PIN || ')'
    )                                            AS FULL_NAME,
    e.DEPARTMENT,
    l.PUNCH_TS,
    TO_DATE(l.PUNCH_TS)                          AS PUNCH_DATE,
    TO_CHAR(l.PUNCH_TS, 'HH24:MI:SS')            AS PUNCH_TIME,
    l.PUNCH_TYPE,
    -- The readable name, resolved through the dimension rather than stored on
    -- the row. Renaming a punch type updates every row ever recorded, past and
    -- future, in one statement. COALESCE covers a code from firmware we have
    -- not seen: better "Type 7" in the UI than a NULL that hides the punch.
    COALESCE(pt.PUNCH_NAME, 'Type ' || COALESCE(l.PUNCH_TYPE::VARCHAR, '?'))
                                                 AS PUNCH_NAME,
    CASE l.VERIFY_MODE
        WHEN 1  THEN 'Fingerprint'
        WHEN 2  THEN 'PIN'
        WHEN 3  THEN 'Password'
        WHEN 4  THEN 'Card'
        WHEN 15 THEN 'Face'
        WHEN 0  THEN 'Password'
        ELSE 'Mode ' || COALESCE(l.VERIFY_MODE::VARCHAR, '?')
    END                                          AS VERIFY_LABEL,
    -- The site the punch was taken at, stamped by the terminal at punch time.
    -- Exposed as DISTRICT as well, because that is what people call it: it is
    -- the geographic dimension every dashboard filter wants. Note it belongs to
    -- the PUNCH, not to the person - somebody who works across two sites has
    -- punches in both, and nobody has a fixed district anywhere in this schema.
    l.LOCATION,
    l.LOCATION                                   AS DISTRICT,
    l.DEVICE_SN,
    l.RECEIVED_AT,
    -- First punch of the person's day. The device's PUNCH_TYPE (State) field
    -- reads 0 on most Horus units regardless of direction, so direction is
    -- derived from ordering rather than trusted from the device.
    ROW_NUMBER() OVER (
        PARTITION BY l.EMPLOYEE_PIN, TO_DATE(l.PUNCH_TS)
        ORDER BY l.PUNCH_TS
    )                                            AS PUNCH_SEQ
FROM ATTENDANCE_LOGS l
LEFT JOIN EMPLOYEES e    ON e.DEVICE_PIN = l.EMPLOYEE_PIN
LEFT JOIN PUNCH_TYPES pt ON pt.CODE      = l.PUNCH_TYPE
-- Voided punches disappear from every view and every count, but the row
-- survives with who voided it and when. Attendance feeds pay and settles
-- disputes: "why is that punch missing" has to have an answer.
WHERE l.VOIDED_AT IS NULL;


-- ---------------------------------------------------------------------------
-- v_staff_presence - who is in the office right now
--
-- The four states were defined by the user, and the definition matters more
-- than the query: this is the first thing in the whole system that makes a
-- judgement about a person from their punches, so the rule is written down
-- rather than implied.
--
--   most recent punch TODAY is Clock In (0) or Back on Seat (3)
--                                          -> In the Office
--   Clock Out (1)                          -> Left the Office
--   Stepping Out (2)                       -> Temporarily Out of Office
--   no punch at all today                  -> Not in Today
--
-- "Today" only. Somebody who clocked in yesterday and never clocked out is
-- Not in Today, not still present - a forgotten Clock Out would otherwise
-- leave them showing as in the office indefinitely, and nothing in the data
-- distinguishes that from a real overnight shift.
--
-- Still no Late and no Absent. No shift rules exist, so those remain
-- uninventable; "Not in Today" is a fact, "Absent" would be a judgement.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_STAFF_PRESENCE AS
WITH todays_last AS (
    SELECT
        EMPLOYEE_PIN,
        PUNCH_TYPE,
        PUNCH_TS,
        LOCATION,
        ROW_NUMBER() OVER (PARTITION BY EMPLOYEE_PIN ORDER BY PUNCH_TS DESC) AS RN
    FROM ATTENDANCE_LOGS
    WHERE VOIDED_AT IS NULL
      AND TO_DATE(PUNCH_TS) = DEVICE_TODAY()
)
SELECT
    r.DEVICE_PIN,
    r.SF_ID,
    r.FIRST_NAME,
    r.LAST_NAME,
    r.FULL_NAME,
    r.DEPARTMENT,
    r.GENDER,
    r.TEMPLATE_COUNT,
    t.PUNCH_TYPE                       AS LAST_PUNCH_TYPE,
    pt.PUNCH_NAME                      AS LAST_PUNCH_NAME,
    t.PUNCH_TS                         AS LAST_PUNCH_TS,
    TO_CHAR(t.PUNCH_TS, 'HH24:MI:SS')  AS LAST_PUNCH_TIME,
    -- Where they last punched today. NULL for anyone with no punch, which is
    -- the important consequence: nobody has a fixed district in this schema,
    -- so filtering presence by district necessarily hides "Not in Today" -
    -- there is no site to attribute them to. The web app says so rather than
    -- letting people quietly vanish from a filtered count.
    t.LOCATION                         AS DISTRICT,
    CASE
        WHEN t.PUNCH_TYPE IS NULL    THEN 'Not in Today'
        WHEN t.PUNCH_TYPE IN (0, 3)  THEN 'In the Office'
        WHEN t.PUNCH_TYPE = 1        THEN 'Left the Office'
        WHEN t.PUNCH_TYPE = 2        THEN 'Temporarily Out of Office'
        -- A code from firmware we have not seen. Surfaced rather than folded
        -- into one of the four, so it shows up instead of quietly miscounting.
        ELSE 'Unknown (' || t.PUNCH_TYPE::VARCHAR || ')'
    END                                AS PRESENCE
-- V_EMPLOYEE_ROSTER already excludes tombstoned staff, so deleted people
-- cannot appear here or inflate the head-count.
FROM V_EMPLOYEE_ROSTER r
LEFT JOIN todays_last t  ON t.EMPLOYEE_PIN = r.DEVICE_PIN AND t.RN = 1
LEFT JOIN PUNCH_TYPES pt ON pt.CODE        = t.PUNCH_TYPE;


-- ---------------------------------------------------------------------------
-- v_daily_presence - one row per person per day they appeared
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_DAILY_PRESENCE AS
SELECT
    EMPLOYEE_PIN,
    PUNCH_DATE,
    MIN(PUNCH_TS)   AS FIRST_PUNCH_TS,
    MAX(PUNCH_TS)   AS LAST_PUNCH_TS,
    COUNT(*)        AS PUNCH_COUNT,
    -- Only meaningful once someone has punched at least twice.
    IFF(COUNT(*) > 1,
        DATEDIFF('minute', MIN(PUNCH_TS), MAX(PUNCH_TS)) / 60.0,
        NULL)       AS SPAN_HOURS
FROM V_ATTENDANCE_STREAM
GROUP BY EMPLOYEE_PIN, PUNCH_DATE;


-- ---------------------------------------------------------------------------
-- v_device_health - is the terminal still talking to us?
--
-- A Horus H1 with Realtime=1 should touch the server at least every Delay
-- seconds (10 in our OPTIONS block) even with no punches, because it polls
-- getrequest. A gap of more than a few minutes means the link is down.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_DEVICE_HEALTH AS
SELECT
    DEVICE_SN,
    MAX(RECEIVED_AT)                                        AS LAST_SEEN_AT,
    -- Both sides UTC: RECEIVED_AT is written by SYSDATE() and compared against
    -- SYSDATE(). Mixing a UTC column with a session-local "now" would make a
    -- healthy device look eight hours stale, or a dead one look current.
    DATEDIFF('second', MAX(RECEIVED_AT), SYSDATE())         AS SECONDS_SINCE_SEEN,
    COUNT(*)                                                AS TOTAL_PUNCHES
FROM ATTENDANCE_LOGS
WHERE DEVICE_SN IS NOT NULL
GROUP BY DEVICE_SN;
