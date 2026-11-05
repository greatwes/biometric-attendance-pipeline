#!/usr/bin/env python3
"""Test a Snowflake credential before wiring it into n8n.

The point is isolation. If n8n's "Test" button fails you cannot tell whether
the credential is wrong, the grants are wrong, or n8n is mishandling it. This
script exercises the credential directly, so a failure here is the credential
and a pass here plus a failure in n8n is n8n.

    pip install "snowflake-connector-python[secure-local-storage]"

    # 1. Prove your account identifier / warehouse / role are right.
    #    Opens a browser - this is your normal SSO login.
    python tools/test_snowflake_auth.py --mode sso --account <acct> --user <you>

    # 2. Test a programmatic access token (Route B).
    python tools/test_snowflake_auth.py --mode pat --account <acct> --user <you>

    # 3. Test a key pair (Routes A / C).
    python tools/test_snowflake_auth.py --mode keypair --account <acct> \\
        --user ZK_ATTENDANCE_SVC_USER --key ~/zk-keys/zk_attendance_key.p8

Secrets are read from the environment if set, otherwise prompted for, so they
never end up in your shell history:
    SNOWFLAKE_PAT            the token
    SNOWFLAKE_KEY_PASSPHRASE the .p8 passphrase
"""

import argparse
import getpass
import os
import sys

try:
    import snowflake.connector
except ImportError:
    sys.exit('snowflake-connector-python is not installed. Run:\n'
             '    pip install "snowflake-connector-python[secure-local-storage]"')

# Microsoft Store Python breaks snowflake-connector before it authenticates.
#
# On every connect the driver collects client metadata, which calls
# platform.libc_ver() - and that function opens sys.executable to sniff the
# libc string out of the binary. Store Python's sys.executable is an MSIX
# reparse point under WindowsApps that cannot be opened, so the whole connect
# dies with "OSError: [Errno 22] Invalid argument: ...python.exe".
#
# The failure looks exactly like an auth failure - it is reported identically
# for every credential mode - so without this you conclude your token is bad
# when it was never sent. A libc version is meaningless on Windows, so
# returning empty strings costs nothing.
if sys.platform == 'win32':
    import platform as _platform
    _platform.libc_ver = lambda *args, **kwargs: ('', '')


def load_private_key(path, passphrase):
    """Read a PKCS#8 .p8 file into the DER bytes the connector wants."""
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives import serialization

    with open(os.path.expanduser(path), 'rb') as fh:
        key = serialization.load_pem_private_key(
            fh.read(),
            password=passphrase.encode() if passphrase else None,
            backend=default_backend(),
        )
    return key.private_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )


def attempt(label, params, args):
    """Connect, run a couple of real queries, report plainly."""
    base = {
        'account':   args.account,
        'user':      args.user,
        'login_timeout': 30,
    }
    for key, value in (('role', args.role), ('warehouse', args.warehouse),
                       ('database', args.database), ('schema', args.schema)):
        if value:
            base[key] = value
    base.update(params)

    print('\n--- %s ---' % label)
    conn = None
    try:
        conn = snowflake.connector.connect(**base)
        cur = conn.cursor()

        cur.execute('SELECT CURRENT_USER(), CURRENT_ROLE(), '
                    'CURRENT_WAREHOUSE(), CURRENT_DATABASE(), CURRENT_SCHEMA()')
        who = cur.fetchone()
        print('  connected')
        print('    user=%s  role=%s  warehouse=%s' % (who[0], who[1], who[2]))
        print('    database=%s  schema=%s' % (who[3], who[4]))

        if not who[2]:
            print('  WARNING: no warehouse in session. Queries will fail even '
                  'though auth worked. Pass --warehouse.')

        # Auth working is not the same as the pipeline working. The grants
        # matter just as much, and they fail differently.
        target = '%s.ATTENDANCE' % (args.database or 'DEVELOPMENT')
        try:
            cur.execute('SELECT COUNT(*) FROM %s.ATTENDANCE_LOGS' % target)
            print('  can read %s.ATTENDANCE_LOGS (%d rows)' % (target, cur.fetchone()[0]))
        except Exception as e:
            print('  cannot read %s.ATTENDANCE_LOGS' % target)
            print('    %s' % str(e).strip().splitlines()[0])
            print('    -> auth is fine; this is a grants or schema problem.')
            print('       Has sql/01_snowflake_schema.sql been run?')
            return True   # auth still succeeded

        return True

    except Exception as e:
        first = str(e).strip().splitlines()[0]
        print('  FAILED: %s' % first)
        explain(first)
        return False
    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass


def explain(msg):
    """Translate the errors that actually come up into what to do next."""
    low = msg.lower()
    if 'network policy' in low:
        print('    -> This is the one PAT prerequisite you cannot self-serve.')
        print('       A human user must be covered by a network policy to')
        print('       authenticate with a token. Ask an admin to apply one,')
        print('       or fall back to Route A (service account).')
    elif 'programmatic access token is invalid' in low:
        # Deliberately separate from the JWT case below. These two produce
        # similar wording and completely different fixes, and conflating them
        # sends you hunting for a key-pair problem that does not exist.
        print('    -> The token reached Snowflake and was rejected. In order of')
        print('       likelihood:')
        print('       1. ROLE_RESTRICTION mismatch. A token minted with')
        print('          ROLE_RESTRICTION only works when the session asks for')
        print('          exactly that role. Re-run with --role <that role>.')
        print('       2. Not covered by a network policy. Snowflake often')
        print('          reports this as a generic invalid-token error rather')
        print('          than naming the policy. Needs an admin.')
        print('       3. The secret was mis-copied. It prints once; a truncated')
        print('          paste looks identical to a wrong token.')
        print('       Check it exists and has not expired:')
        print('          SHOW USER PROGRAMMATIC ACCESS TOKENS;')
    elif 'jwt' in low:
        print('    -> Key-pair problem: the public key in Snowflake does not')
        print('       match this private key. Compare with')
        print('       DESC USER <name> -> RSA_PUBLIC_KEY_FP.')
    elif 'incorrect username or password' in low:
        print('    -> For --mode pat this often means the token was rejected')
        print('       as a password. See the summary at the end.')
    elif 'insufficient privileges' in low:
        print('    -> Authentication worked; authorisation did not. The role')
        print('       lacks a grant. Check GRANT USAGE on database/schema.')
    elif 'does not exist' in low and 'warehouse' in low:
        print('    -> Warehouse name is wrong, or your role has no USAGE on it.')
    elif 'account' in low and ('not exist' in low or 'incorrect' in low):
        print('    -> Account identifier is wrong. It is usually')
        print('       <org>-<account> or <locator>.<region>, not the URL.')


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--mode', required=True, choices=['sso', 'pat', 'keypair'])
    p.add_argument('--account', required=True,
                   help='Account identifier, e.g. abc12345.eu-west-1 or myorg-myacct')
    p.add_argument('--user', required=True)
    p.add_argument('--role')
    p.add_argument('--warehouse')
    p.add_argument('--database', default='DEVELOPMENT')
    p.add_argument('--schema', default='ATTENDANCE')
    p.add_argument('--key', help='Path to the .p8 private key (keypair mode)')
    args = p.parse_args()

    print('\nSnowflake auth test')
    print('  account=%s  user=%s  mode=%s' % (args.account, args.user, args.mode))

    if args.mode == 'sso':
        ok = attempt('SSO (external browser)',
                     {'authenticator': 'externalbrowser'}, args)
        print('\n%s\n' % ('SSO works. Your account identifier, role and warehouse are '
                          'correct -\nreuse exactly those values for the other modes.'
                          if ok else 'SSO failed. Fix these basics before trying anything else.'))
        return 0 if ok else 1

    if args.mode == 'keypair':
        if not args.key:
            p.error('--key is required for keypair mode')
        passphrase = os.environ.get('SNOWFLAKE_KEY_PASSPHRASE') or \
            getpass.getpass('Private key passphrase (blank if none): ')
        try:
            pkey = load_private_key(args.key, passphrase)
        except Exception as e:
            print('\nCould not read the private key: %s' % e)
            print('Check the path, and that the passphrase is right.')
            return 1
        ok = attempt('Key pair', {'private_key': pkey}, args)
        print('\n%s\n' % ('Key pair works. Put the FULL .p8 file (BEGIN/END lines '
                          'included)\ninto the n8n credential Private Key field.'
                          if ok else 'Key pair failed - see above.'))
        return 0 if ok else 1

    # PAT. Two ways a driver can present a token, and the difference decides
    # whether n8n can use it at all: the n8n Snowflake credential only offers
    # Password and Key Pair, with no field for an authenticator. So a token
    # that works ONLY with authenticator=PROGRAMMATIC_ACCESS_TOKEN is a token
    # n8n cannot use, however valid it is.
    token = os.environ.get('SNOWFLAKE_PAT') or getpass.getpass('Programmatic access token: ')

    as_password = attempt('PAT sent as password (what n8n does)',
                          {'password': token}, args)
    as_authed = False
    if not as_password:
        as_authed = attempt('PAT with authenticator=PROGRAMMATIC_ACCESS_TOKEN',
                            {'password': token,
                             'authenticator': 'PROGRAMMATIC_ACCESS_TOKEN'}, args)

    print('\n' + '=' * 68)
    if as_password:
        print('PAT works as a plain password.')
        print('n8n can use it: Snowflake credential -> Authentication: Password,')
        print('Username = your login, Password = the token.')
    elif as_authed:
        print('PAT works, but ONLY with authenticator=PROGRAMMATIC_ACCESS_TOKEN.')
        print('n8n\'s Snowflake credential has no field for that, so it cannot')
        print('use this token. Go to Route A (service account + key pair).')
    else:
        print('PAT did not authenticate either way. Read the error above -')
        print('if it mentions a network policy, that is the prerequisite you')
        print('cannot self-serve.')
    print('=' * 68 + '\n')

    return 0 if (as_password or as_authed) else 1


if __name__ == '__main__':
    sys.exit(main())
