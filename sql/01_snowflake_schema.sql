-- ============================================================================
-- ZK Horus H1 attendance - Snowflake schema
-- Run once as a role that can create objects in your target database.
-- ============================================================================
--
-- IMPORTANT SNOWFLAKE CAVEAT
-- Snowflake does NOT enforce PRIMARY KEY or UNIQUE constraints. They are
-- metadata only. Every constraint below is documentation for humans and for
-- Dataiku's schema introspection - it will not stop a duplicate row.
-- That is why every write path in the n8n workflow is a MERGE keyed on a
-- deterministic business key, never a bare INSERT. ADMS devices re-send a
-- batch whenever they don't see a clean "OK", so duplicates are the norm,
-- not the exception.
-- ============================================================================

USE DATABASE   DEVELOPMENT;      -- your sandbox; tables land here
CREATE SCHEMA IF NOT EXISTS ATTENDANCE;
USE SCHEMA     ATTENDANCE;


-- ---------------------------------------------------------------------------
-- punch_types - what each ADMS state code means
--
-- A dimension rather than a text column on ATTENDANCE_LOGS. The codes are ADMS
-- constants and are permanent; the NAMES are ours and have already changed once
-- ("Check In" became "Back on Seat"). Storing the label alongside every punch
-- would mean rows written before a rename disagree with rows written after, and
-- fixing that is a backfill of the whole history every time somebody rewords a
-- button. Here it is a one-row UPDATE.
--
-- Note 2 and 3 are not in label order: ADMS defines 2 as Break-Out (leaving
-- temporarily) and 3 as Break-In (returning). Do not renumber them to read
-- nicely - the codes have to match what every other ZKTeco device emits.
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


-- ---------------------------------------------------------------------------
-- departments - the admin-managed list the enrolment dropdown offers
--
-- A table, not free text on EMPLOYEES. Typed departments drift immediately
-- across several terminals - "Field Ops" and "Field Operations" are two
-- departments to every dashboard - and no amount of reporting logic fixes that
-- after the fact.
--
-- DELETED_AT is a tombstone for the same reason it is on EMPLOYEES: with
-- multiple devices, an absent row cannot be told apart from one not yet pulled.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS DEPARTMENTS (
    NAME        VARCHAR(128)  NOT NULL,
    DELETED_AT  TIMESTAMP_NTZ,
    DEVICE_SN   VARCHAR(64),
    CREATED_AT  TIMESTAMP_NTZ DEFAULT SYSDATE(),
    UPDATED_AT  TIMESTAMP_NTZ DEFAULT SYSDATE(),
    CONSTRAINT PK_DEPARTMENTS PRIMARY KEY (NAME)
);


-- ---------------------------------------------------------------------------
-- employees - one row per PIN enrolled on the device
--
-- NOTE: a stock ZK USERINFO record has no department or gender field, and a
-- real ZKTeco terminal will never populate them. OUR app does: it sends them as
-- extra Dept= and Gender= pairs, which is safe because USERINFO is key-value
-- rather than positional. Both are therefore populated for staff enrolled on
-- our terminals and NULL for anyone arriving from stock firmware, so treat them
-- as "best known" rather than authoritative and keep HR master data as the
-- source of truth (see 02_dataiku_views.sql).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS EMPLOYEES (
    -- The staff member's SF ID. Named DEVICE_PIN because that is what the ADMS
    -- protocol calls it and what the terminal's matcher keys on; they are the
    -- same value and deliberately not two columns. Every label a human sees
    -- says "SF ID".
    DEVICE_PIN   VARCHAR(32)   NOT NULL,
    -- Split names. FULL_NAME is kept and still populated, because a stock ZK
    -- terminal sends only `Name=` and has no concept of the split - for those
    -- rows FIRST_NAME and LAST_NAME are NULL and FULL_NAME is all there is.
    FIRST_NAME   VARCHAR(128),
    LAST_NAME    VARCHAR(128),
    FULL_NAME    VARCHAR(255),
    DEPARTMENT   VARCHAR(128),
    GENDER       VARCHAR(32),
    CARD_NO      VARCHAR(64),
    PRIVILEGE    NUMBER(3,0),                -- 0 = user, 14 = admin
    STATUS       VARCHAR(16)   DEFAULT 'ACTIVE',
    -- Tombstone for multi-device sync. NULL means the person is current.
    --
    -- A deletion has to be a WRITE, not an absence: with several terminals,
    -- "row missing here but present there" is indistinguishable from "not
    -- pulled yet", so a device that deleted somebody would restore them on its
    -- next pull and deletion would silently stop working.
    --
    -- Two rules go with this column:
    --   1. Deleting MUST also bump UPDATED_AT, because incremental pull selects
    --      on UPDATED_AT > :since. A tombstone that does not move UPDATED_AT is
    --      never sent to anyone.
    --   2. A USERINFO push MUST NOT clear it. A device that has not yet learned
    --      about the deletion will keep pushing that person's roster row, and
    --      clearing it there would resurrect them - the exact bug this column
    --      exists to prevent. See the MERGE in parse-adms-request.js.
    --
    -- Re-enrolling a returning employee therefore needs an explicit un-delete;
    -- that decision is open, see SYNC-DESIGN.md section 2.1.
    DELETED_AT   TIMESTAMP_NTZ,
    DEVICE_SN    VARCHAR(64),
    CREATED_AT   TIMESTAMP_NTZ DEFAULT SYSDATE(),
    UPDATED_AT   TIMESTAMP_NTZ DEFAULT SYSDATE(),
    CONSTRAINT PK_EMPLOYEES PRIMARY KEY (DEVICE_PIN),  -- not enforced, see above
    -- Declared so Dataiku and any BI tool can infer the join. Not enforced, so
    -- a department typed on a terminal that has not yet pulled the list will
    -- still be accepted rather than rejected - which is the behaviour we want:
    -- losing an enrolment over a spelling is worse than an unmatched label.
    CONSTRAINT FK_EMPLOYEES_DEPT FOREIGN KEY (DEPARTMENT) REFERENCES DEPARTMENTS (NAME)
);


-- ---------------------------------------------------------------------------
-- biometric_templates - one row per (PIN, finger)
--
-- TEMPLATE_DATA holds the base64 string exactly as the device sent it. Do not
-- decode, re-encode or trim it: it has to go back to the device byte-identical
-- if you ever push enrolment to a second terminal.
--
-- This is biometric data. Under GDPR/NDPR it is special-category personal data.
-- Restrict this table with a Snowflake row/column policy or a dedicated role -
-- do not let it flow into general-purpose BI datasets.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS BIOMETRIC_TEMPLATES (
    EMPLOYEE_PIN  VARCHAR(32)   NOT NULL,
    FINGER_INDEX  NUMBER(2,0)   NOT NULL,    -- 0-9
    TEMPLATE_DATA VARCHAR(16777216),         -- base64; ~1-3 KB in practice
    TEMPLATE_TYPE VARCHAR(16),               -- ZK "Type": 1=fingerprint, 9=face, ...
    TEMPLATE_SIZE NUMBER(10,0),
    IS_VALID      BOOLEAN       DEFAULT TRUE,
    DEVICE_SN     VARCHAR(64),
    UPDATED_AT    TIMESTAMP_NTZ DEFAULT SYSDATE(),
    CONSTRAINT PK_BIOTEMPLATES PRIMARY KEY (EMPLOYEE_PIN, FINGER_INDEX),
    CONSTRAINT FK_BIOTEMPLATES_EMP FOREIGN KEY (EMPLOYEE_PIN) REFERENCES EMPLOYEES (DEVICE_PIN)
);


-- ---------------------------------------------------------------------------
-- attendance_logs - one row per punch
--
-- LOG_ID is the idempotency key: DEVICE_SN|PIN|TIMESTAMP. A device that
-- re-sends the same batch produces the same LOG_IDs, and the MERGE no-ops.
--
-- PUNCH_TS is TIMESTAMP_NTZ because the device sends naive local wall-clock
-- time with no offset. Storing it as TIMESTAMP_TZ would mean inventing a
-- timezone at write time; instead we keep what the device said and convert at
-- read time using a single configured zone.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ATTENDANCE_LOGS (
    LOG_ID       VARCHAR(160)  NOT NULL,
    EMPLOYEE_PIN VARCHAR(32)   NOT NULL,
    PUNCH_TS     TIMESTAMP_NTZ NOT NULL,
    PUNCH_TYPE   NUMBER(3,0),                -- 0=Clock In 1=Clock Out 2=Check Out 3=Check In
    VERIFY_MODE  NUMBER(3,0),                -- 1=fingerprint 3=password 4=card 15=face
    -- The site the punch was taken at, stamped by the terminal at punch time.
    -- Sent in the ATTLOG work-code slot, which our app repurposes because a
    -- stock device leaves it empty; see parse-adms-request.js. NULL for punches
    -- taken before a location was configured on that device.
    LOCATION     VARCHAR(128),
    DEVICE_SN    VARCHAR(64),
    RECEIVED_AT  TIMESTAMP_NTZ DEFAULT SYSDATE(),
    CONSTRAINT PK_ATTLOG PRIMARY KEY (LOG_ID),
    -- The join that turns PUNCH_TYPE into a readable name. V_ATTENDANCE_STREAM
    -- exposes it as PUNCH_NAME; query the view, not this column.
    CONSTRAINT FK_ATTLOG_TYPE FOREIGN KEY (PUNCH_TYPE) REFERENCES PUNCH_TYPES (CODE),
    -- Declared for joins, deliberately never enforced even in spirit: a punch
    -- can arrive for a PIN whose USERINFO record was never pushed, and it must
    -- still be stored. Attendance also has to outlive the roster row, so this
    -- must never become a cascade.
    CONSTRAINT FK_ATTLOG_EMP FOREIGN KEY (EMPLOYEE_PIN) REFERENCES EMPLOYEES (DEVICE_PIN)
);

-- Almost every dashboard query filters on the punch date. Clustering keeps
-- pruning effective once this table passes a few million rows. Below that it
-- costs more in maintenance credits than it saves - leave it commented out
-- until the table is actually large.
-- ALTER TABLE ATTENDANCE_LOGS CLUSTER BY (TO_DATE(PUNCH_TS));


-- ---------------------------------------------------------------------------
-- raw_push_log - verbatim capture of everything the device sends
--
-- Turn this on for the first few days of a new device. It is the only reliable
-- way to learn what your specific firmware actually emits, because ZK field
-- names differ between builds. Once the parser is confirmed, set
-- CAPTURE_RAW = false in the n8n Code node.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS RAW_PUSH_LOG (
    -- A surrogate key, because this table has no natural one: the same device
    -- can legitimately send byte-identical bodies a second apart, so there is
    -- nothing in the payload to key on. AUTOINCREMENT gives every row an
    -- identity to reference in a bug report.
    PUSH_ID      NUMBER        AUTOINCREMENT START 1 INCREMENT 1,
    RECEIVED_AT  TIMESTAMP_NTZ DEFAULT SYSDATE(),
    DEVICE_SN    VARCHAR(64),
    HTTP_METHOD  VARCHAR(8),
    TABLE_PARAM  VARCHAR(64),
    QUERY_STRING VARCHAR(4096),
    RAW_BODY     VARCHAR(16777216),
    CONSTRAINT PK_RAW_PUSH_LOG PRIMARY KEY (PUSH_ID)
);

-- Keep the raw capture from growing forever.
-- ALTER TABLE RAW_PUSH_LOG SET DATA_RETENTION_TIME_IN_DAYS = 7;
