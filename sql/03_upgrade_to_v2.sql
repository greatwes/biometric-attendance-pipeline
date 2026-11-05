-- ============================================================================
-- Upgrade an ALREADY-POPULATED DEVELOPMENT.ATTENDANCE to the v2 shape.
--
-- Run this instead of re-running 01_snowflake_schema.sql if the tables already
-- hold rows. It is idempotent: every statement is either IF NOT EXISTS, a
-- MERGE, or an ALTER that Snowflake accepts twice. Re-running it is safe and
-- costs nothing.
--
-- What v2 adds:
--   * FIRST_NAME / LAST_NAME on EMPLOYEES, backfilled from FULL_NAME
--   * PUNCH_TYPES, so a punch has a readable name that is not frozen into the
--     row at write time
--   * DEPARTMENTS, so the enrolment dropdown has one authoritative list
--   * declared PRIMARY KEY / FOREIGN KEY on every table
--
-- Snowflake still does not enforce any of these constraints. They exist for
-- humans and for Dataiku's schema introspection, which is what makes the tables
-- join up automatically in the flow.
-- ============================================================================

USE DATABASE DEVELOPMENT;
USE SCHEMA   ATTENDANCE;


-- ---------------------------------------------------------------------------
-- 1. Dimensions first - EMPLOYEES gets a foreign key pointing at DEPARTMENTS,
--    so it has to exist before that constraint can be declared.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PUNCH_TYPES (
    CODE        NUMBER(3,0)  NOT NULL,
    PUNCH_NAME  VARCHAR(64)  NOT NULL,
    DESCRIPTION VARCHAR(255),
    CONSTRAINT PK_PUNCH_TYPES PRIMARY KEY (CODE)
);

MERGE INTO PUNCH_TYPES t
USING (
    SELECT 0 AS CODE, 'Clock In'     AS PUNCH_NAME, 'Start of the working day'        AS DESCRIPTION
    UNION ALL SELECT 1, 'Clock Out',    'End of the working day'
    UNION ALL SELECT 2, 'Stepping Out', 'Temporary exit. ADMS Break-Out'
    UNION ALL SELECT 3, 'Back on Seat', 'Returning from a temporary exit. ADMS Break-In'
) s ON t.CODE = s.CODE
WHEN MATCHED THEN UPDATE SET t.PUNCH_NAME = s.PUNCH_NAME, t.DESCRIPTION = s.DESCRIPTION
WHEN NOT MATCHED THEN INSERT (CODE, PUNCH_NAME, DESCRIPTION)
                      VALUES (s.CODE, s.PUNCH_NAME, s.DESCRIPTION);

CREATE TABLE IF NOT EXISTS DEPARTMENTS (
    NAME        VARCHAR(128)  NOT NULL,
    DELETED_AT  TIMESTAMP_NTZ,
    DEVICE_SN   VARCHAR(64),
    CREATED_AT  TIMESTAMP_NTZ DEFAULT SYSDATE(),
    UPDATED_AT  TIMESTAMP_NTZ DEFAULT SYSDATE(),
    CONSTRAINT PK_DEPARTMENTS PRIMARY KEY (NAME)
);

-- Seed from what staff are already filed under, so the list is not empty on the
-- first device that pulls it.
MERGE INTO DEPARTMENTS d
USING (
    SELECT DISTINCT TRIM(DEPARTMENT) AS NAME
    FROM EMPLOYEES
    WHERE DEPARTMENT IS NOT NULL AND TRIM(DEPARTMENT) <> ''
) s ON d.NAME = s.NAME
WHEN NOT MATCHED THEN INSERT (NAME) VALUES (s.NAME);


-- ---------------------------------------------------------------------------
-- 2. Split names on EMPLOYEES
--
-- FULL_NAME is kept, not dropped. A stock ZKTeco terminal sends only `Name=`
-- and has no concept of a split, so for anything enrolled on other firmware
-- FULL_NAME stays the only real value.
-- ---------------------------------------------------------------------------
ALTER TABLE EMPLOYEES ADD COLUMN IF NOT EXISTS FIRST_NAME VARCHAR(128);
ALTER TABLE EMPLOYEES ADD COLUMN IF NOT EXISTS LAST_NAME  VARCHAR(128);

-- Backfill by splitting on the FIRST space: everything before it is the given
-- name, the rest is the surname. Right for "Ada Obi" and "Ada Grace Obi"; wrong
-- for anyone entered surname-first, which is common enough here that the staff
-- list on the device is where those get corrected.
--
-- Only touches rows where both are still NULL, so it cannot overwrite a name a
-- device has since pushed properly, and re-running it is harmless.
UPDATE EMPLOYEES
SET FIRST_NAME = CASE
                     WHEN POSITION(' ' IN TRIM(FULL_NAME)) > 0
                     THEN SPLIT_PART(TRIM(FULL_NAME), ' ', 1)
                     ELSE TRIM(FULL_NAME)
                 END,
    LAST_NAME  = CASE
                     WHEN POSITION(' ' IN TRIM(FULL_NAME)) > 0
                     THEN TRIM(SUBSTR(TRIM(FULL_NAME), POSITION(' ' IN TRIM(FULL_NAME)) + 1))
                     ELSE NULL
                 END
WHERE FIRST_NAME IS NULL
  AND LAST_NAME  IS NULL
  AND FULL_NAME  IS NOT NULL
  AND TRIM(FULL_NAME) <> '';


-- ---------------------------------------------------------------------------
-- 3. Surrogate key for RAW_PUSH_LOG
--
-- Snowflake cannot add an AUTOINCREMENT column to an existing table, so this
-- backfills a sequence value instead. If the table is empty or you no longer
-- need the raw capture, dropping and recreating it from 01_snowflake_schema.sql
-- is cleaner than either.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS SEQ_RAW_PUSH_LOG START = 1 INCREMENT = 1;
ALTER TABLE RAW_PUSH_LOG ADD COLUMN IF NOT EXISTS PUSH_ID NUMBER;
UPDATE RAW_PUSH_LOG SET PUSH_ID = SEQ_RAW_PUSH_LOG.NEXTVAL WHERE PUSH_ID IS NULL;


-- ---------------------------------------------------------------------------
-- 4. Declared keys
--
-- Run this whole block as ONE statement (select it all and execute).
--
-- Why a scripting block rather than plain ALTERs. Snowflake has neither
-- "ADD CONSTRAINT IF NOT EXISTS" nor "DROP CONSTRAINT IF EXISTS" - the second
-- one is not syntax at all, which is what broke the first version of this
-- script. So there is no way to write a re-runnable constraint change as flat
-- DDL: adding one that already exists is an error, and you cannot conditionally
-- drop it first.
--
-- Do NOT "fix" this by dropping the primary keys first. PK_EMPLOYEES,
-- PK_BIOTEMPLATES and PK_ATTLOG already exist - they came from the CREATE TABLE
-- statements in 01_snowflake_schema.sql - and once the foreign keys below point
-- at EMPLOYEES.DEVICE_PIN, dropping that primary key is refused unless you add
-- CASCADE, which would silently take those foreign keys with it.
--
-- Each statement gets its own handler, so "already exists" is counted and
-- skipped while a real failure still stops nothing else from being applied.
-- Re-running is free and reports how much was already in place.
--
-- Reminder: Snowflake enforces none of these. They are metadata, for humans and
-- for Dataiku's schema introspection, which is what makes these tables join up
-- automatically in the flow.
-- ---------------------------------------------------------------------------
DECLARE
    applied INT DEFAULT 0;
    present INT DEFAULT 0;
BEGIN
    BEGIN
        ALTER TABLE EMPLOYEES ADD CONSTRAINT PK_EMPLOYEES PRIMARY KEY (DEVICE_PIN);
        applied := applied + 1;
    EXCEPTION WHEN OTHER THEN present := present + 1;
    END;

    BEGIN
        ALTER TABLE EMPLOYEES ADD CONSTRAINT FK_EMPLOYEES_DEPT
            FOREIGN KEY (DEPARTMENT) REFERENCES DEPARTMENTS (NAME);
        applied := applied + 1;
    EXCEPTION WHEN OTHER THEN present := present + 1;
    END;

    BEGIN
        ALTER TABLE BIOMETRIC_TEMPLATES ADD CONSTRAINT PK_BIOTEMPLATES
            PRIMARY KEY (EMPLOYEE_PIN, FINGER_INDEX);
        applied := applied + 1;
    EXCEPTION WHEN OTHER THEN present := present + 1;
    END;

    BEGIN
        ALTER TABLE BIOMETRIC_TEMPLATES ADD CONSTRAINT FK_BIOTEMPLATES_EMP
            FOREIGN KEY (EMPLOYEE_PIN) REFERENCES EMPLOYEES (DEVICE_PIN);
        applied := applied + 1;
    EXCEPTION WHEN OTHER THEN present := present + 1;
    END;

    BEGIN
        ALTER TABLE ATTENDANCE_LOGS ADD CONSTRAINT PK_ATTLOG PRIMARY KEY (LOG_ID);
        applied := applied + 1;
    EXCEPTION WHEN OTHER THEN present := present + 1;
    END;

    BEGIN
        ALTER TABLE ATTENDANCE_LOGS ADD CONSTRAINT FK_ATTLOG_TYPE
            FOREIGN KEY (PUNCH_TYPE) REFERENCES PUNCH_TYPES (CODE);
        applied := applied + 1;
    EXCEPTION WHEN OTHER THEN present := present + 1;
    END;

    BEGIN
        ALTER TABLE ATTENDANCE_LOGS ADD CONSTRAINT FK_ATTLOG_EMP
            FOREIGN KEY (EMPLOYEE_PIN) REFERENCES EMPLOYEES (DEVICE_PIN);
        applied := applied + 1;
    EXCEPTION WHEN OTHER THEN present := present + 1;
    END;

    BEGIN
        ALTER TABLE RAW_PUSH_LOG ADD CONSTRAINT PK_RAW_PUSH_LOG PRIMARY KEY (PUSH_ID);
        applied := applied + 1;
    EXCEPTION WHEN OTHER THEN present := present + 1;
    END;

    RETURN 'constraints added: ' || applied || ', already present or skipped: ' || present;
END;


-- If you would rather see exactly what is there than trust the counters:
--
-- SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE, TABLE_NAME
-- FROM   DEVELOPMENT.INFORMATION_SCHEMA.TABLE_CONSTRAINTS
-- WHERE  TABLE_SCHEMA = 'ATTENDANCE'
--   AND  CONSTRAINT_NAME NOT LIKE 'SYS_%'
-- ORDER  BY TABLE_NAME, CONSTRAINT_TYPE;


-- ---------------------------------------------------------------------------
-- 5. Check what you got
-- ---------------------------------------------------------------------------
SELECT 'employees'  AS TABLE_NAME, COUNT(*) AS ROWS,
       COUNT(FIRST_NAME) AS WITH_FIRST_NAME FROM EMPLOYEES
UNION ALL
SELECT 'departments', COUNT(*), NULL FROM DEPARTMENTS
UNION ALL
SELECT 'punch_types', COUNT(*), NULL FROM PUNCH_TYPES
UNION ALL
SELECT 'templates',   COUNT(*), NULL FROM BIOMETRIC_TEMPLATES
UNION ALL
SELECT 'logs',        COUNT(*), NULL FROM ATTENDANCE_LOGS;
