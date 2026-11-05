-- ============================================================================
-- Giving n8n a way into Snowflake
--
-- THE PROBLEM
-- You sign in with SSO. SSO is interactive by definition: browser opens, IdP
-- authenticates you, you click approve. Right for a person, impossible for a
-- workflow firing at 03:00 because a night-shift worker clocked in.
--
-- The n8n Snowflake credential offers Password or Key Pair. Neither is SSO.
--
-- You cannot create Snowflake users. Three routes remain. Try them in the
-- order given - B is self-service and takes five minutes, so start there.
-- Do NOT run this file top to bottom.
-- ============================================================================


-- ############################################################################
-- ROUTE B - programmatic access token   ** TRY THIS FIRST **
-- ############################################################################
--
-- A PAT is a long-lived token that works in place of a password in Snowflake
-- drivers. The reason to start here: Snowflake's docs are explicit that for
-- human users (TYPE = PERSON), "you do not need any special privileges to
-- generate, modify, drop, or display a programmatic access token for
-- yourself." No admin, no ticket, no waiting.
--
-- ONE PREREQUISITE, AND IT MAY STILL NEED AN ADMIN:
-- a human user can *generate* a PAT freely, but must be covered by a network
-- policy to *authenticate* with one. If your org already applies a network
-- policy you are fine. If not, that part needs an administrator. You will
-- find out at step B3, not before - so just try it.

-- B1. Confirm who you are and that you are a PERSON-type user.
SELECT CURRENT_USER(), CURRENT_ACCOUNT(), CURRENT_ROLE(), CURRENT_WAREHOUSE();

-- B2. Mint the token. Replace <your login> with the CURRENT_USER() result.
--
-- DAYS_TO_EXPIRY defaults to 15. Fifteen days is a trap for an attendance
-- system: it stops collecting silently, on a date nobody has in a calendar.
-- Set it long and put the renewal in your calendar the same day.
--
-- ROLE_RESTRICTION is optional for a person user but worth setting - it caps
-- what the token can do to a single role, so a leaked token is not your whole
-- Snowflake identity.
--
--   ALTER USER "<your login>" ADD PROGRAMMATIC ACCESS TOKEN N8N_ZK_ATTENDANCE
--       ROLE_RESTRICTION = '<the role you use for DEVELOPMENT>'
--       DAYS_TO_EXPIRY   = 365
--       COMMENT          = 'n8n ZKTeco attendance ingest';
--
-- The statement prints TOKEN_SECRET exactly once. No other command will ever
-- show it again. Copy it straight into your password manager before you run
-- anything else.

-- B3. Test it before wiring n8n up:
--     python tools/test_snowflake_auth.py --mode pat
--
-- Failure here that mentions a network policy means the prerequisite above is
-- unmet - that is the one thing you cannot self-serve. Ask an admin to apply
-- a network policy to your user, or fall back to Route A.

-- Housekeeping:
--   SHOW USER PROGRAMMATIC ACCESS TOKENS;        -- lists names, never secrets
--   ALTER USER "<you>" REMOVE PROGRAMMATIC ACCESS TOKEN N8N_ZK_ATTENDANCE;
--
-- Max 15 live tokens per user. Rotate by adding a new one, switching n8n over,
-- then removing the old.


-- ############################################################################
-- ROUTE A - a proper service account   (best long-term; needs an admin)
-- ############################################################################
--
-- A dedicated identity with narrow grants, no tie to your personal account,
-- revocable without touching you. This is where the system should end up even
-- if Route B gets it running today.
--
-- Send your Snowflake administrator everything between the ===== markers,
-- plus the public key from "Generating a key pair" at the bottom.

-- ===== BEGIN: for the Snowflake administrator =============================
USE ROLE ACCOUNTADMIN;

CREATE ROLE IF NOT EXISTS ZK_ATTENDANCE_SVC
    COMMENT = 'Service role for the ZKTeco attendance pipeline (n8n)';

-- XSMALL is correct: thousands of tiny statements, never a big scan.
-- 60-second auto-suspend stops an idle terminal burning credits overnight.
CREATE WAREHOUSE IF NOT EXISTS ZK_ATTENDANCE_WH
    WAREHOUSE_SIZE      = 'XSMALL'
    AUTO_SUSPEND        = 60
    AUTO_RESUME         = TRUE
    INITIALLY_SUSPENDED = TRUE;

CREATE USER IF NOT EXISTS ZK_ATTENDANCE_SVC_USER
    DEFAULT_ROLE      = ZK_ATTENDANCE_SVC
    DEFAULT_WAREHOUSE = ZK_ATTENDANCE_WH
    -- TYPE = SERVICE makes non-interactive structural: Snowflake refuses
    -- password auth on these and exempts them from the MFA policies that
    -- would otherwise block an unattended login. Delete this line if your
    -- Snowflake release rejects it; everything else still works.
    TYPE              = SERVICE
    COMMENT = 'ZKTeco attendance ingest. Key-pair only. Owner: HR Analytics.';

GRANT ROLE ZK_ATTENDANCE_SVC TO USER ZK_ATTENDANCE_SVC_USER;

-- Paste the single-line public key from the bottom of this file.
ALTER USER ZK_ATTENDANCE_SVC_USER SET RSA_PUBLIC_KEY = 'PASTE_PUBLIC_KEY_HERE';

GRANT USAGE ON WAREHOUSE ZK_ATTENDANCE_WH       TO ROLE ZK_ATTENDANCE_SVC;
GRANT USAGE ON DATABASE  DEVELOPMENT            TO ROLE ZK_ATTENDANCE_SVC;
GRANT USAGE ON SCHEMA    DEVELOPMENT.ATTENDANCE TO ROLE ZK_ATTENDANCE_SVC;

GRANT SELECT, INSERT, UPDATE ON ALL TABLES    IN SCHEMA DEVELOPMENT.ATTENDANCE
    TO ROLE ZK_ATTENDANCE_SVC;
GRANT SELECT, INSERT, UPDATE ON FUTURE TABLES IN SCHEMA DEVELOPMENT.ATTENDANCE
    TO ROLE ZK_ATTENDANCE_SVC;
GRANT SELECT ON ALL VIEWS    IN SCHEMA DEVELOPMENT.ATTENDANCE TO ROLE ZK_ATTENDANCE_SVC;
GRANT SELECT ON FUTURE VIEWS IN SCHEMA DEVELOPMENT.ATTENDANCE TO ROLE ZK_ATTENDANCE_SVC;

-- No DELETE, no DROP. The pipeline only inserts and merges, so a compromised
-- key cannot erase attendance history.

-- Verify. RSA_PUBLIC_KEY_FP must show a fingerprint, not null. Null almost
-- always means a stray newline got into the pasted key.
DESC USER ZK_ATTENDANCE_SVC_USER;
-- ===== END: for the Snowflake administrator ===============================


-- ############################################################################
-- ROUTE C - key pair on your own user   (probably not available to you)
-- ############################################################################
--
-- CORRECTION TO EARLIER ADVICE. I previously suggested most Snowflake accounts
-- let a user attach an RSA key to their own login. Checking the ALTER USER
-- documentation, that is wrong. Snowflake permits a user to self-modify only:
--
--     DEFAULT_WAREHOUSE, DEFAULT_NAMESPACE, DEFAULT_ROLE,
--     and their session parameter defaults
--
-- RSA_PUBLIC_KEY is not on that list, so it falls under the general rule:
-- "Only the role with the OWNERSHIP privilege on the user, or a higher role,
-- can execute this command to modify most user properties."
--
-- Unless you happen to own your own user object - unusual, and unlikely under
-- SSO provisioning - this route is closed. It costs 20 seconds to be certain.

-- C1. Probe for OWNERSHIP. COMMENT is not self-modifiable either, so it is a
--     safe stand-in: if this succeeds you have OWNERSHIP and Route C is open.
--   ALTER USER "<your login>" SET COMMENT = 'privilege probe';
--
--   Succeeds            -> Route C is open. Run C2, then clear the comment.
--   "Insufficient privileges to operate on user" -> closed. Use Route B or A.

-- C2. Only if C1 succeeded:
--   ALTER USER "<your login>" SET RSA_PUBLIC_KEY = 'PASTE_PUBLIC_KEY_HERE';
--   DESC  USER "<your login>";          -- RSA_PUBLIC_KEY_FP must be set
--   ALTER USER "<your login>" UNSET COMMENT;
--
-- To revoke:  ALTER USER "<your login>" UNSET RSA_PUBLIC_KEY;
--
-- What you accept on Route C, same as Route B: every row the pipeline writes
-- is audited as you, the credential authenticates as your full identity, and
-- ingestion stops the day your account changes. Fine for a pilot. Open the
-- Route A request anyway.


-- ############################################################################
-- Generating a key pair  (Routes A and C only - Route B needs no key)
-- ############################################################################
--
-- Git Bash on Windows, not PowerShell:
--
--   mkdir -p ~/zk-keys && cd ~/zk-keys
--
--   # Private key, PKCS#8. Prompts for a passphrase - choose one and store it;
--   # n8n asks for it too.
--   openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out zk_attendance_key.p8
--
--   # Matching public key.
--   openssl rsa -in zk_attendance_key.p8 -pubout -out zk_attendance_key.pub
--
--   # Print as one continuous line - the format Snowflake wants.
--   grep -v "^-----" zk_attendance_key.pub | tr -d '\n'
--
-- TWO FORMATS, CONSTANTLY MIXED UP:
--   Snowflake RSA_PUBLIC_KEY -> PUBLIC key, one line, no BEGIN/END markers.
--                               That is the grep|tr output above.
--   n8n "Private Key" field  -> the ENTIRE .p8 file including its
--                               -----BEGIN ENCRYPTED PRIVATE KEY----- and
--                               -----END----- lines, line breaks intact.
--
-- The .p8 file is a credential. Never commit, email, or share-drive it. The
-- .pub file is harmless - that is the point of a key pair.


-- ############################################################################
-- Biometric data
-- ############################################################################
-- BIOMETRIC_TEMPLATES holds fingerprint templates: special-category personal
-- data under NDPR/GDPR. The pipeline must write to it; humans doing general
-- reporting should not read it. After 01_snowflake_schema.sql has run:
--
--   REVOKE SELECT ON DEVELOPMENT.ATTENDANCE.BIOMETRIC_TEMPLATES
--       FROM ROLE <your general analyst role>;
