#!/usr/bin/env python3
"""Pretend to be a ZKTeco Horus H1 pushing over ADMS.

Lets you exercise the whole chain - relay, n8n workflow, Snowflake MERGEs,
Dataiku dashboard - without touching the terminal, and lets you tell whether a
problem is in your pipeline or in the device.

    # against the LAN relay
    python simulate_device.py --base http://192.168.1.50:8080

    # straight at n8n, skipping the relay
    python simulate_device.py --base https://automations.example.org/webhook \\
                              --key "your-shared-secret"

    # just the handshake, to check the OPTIONS block comes back
    python simulate_device.py --base http://192.168.1.50:8080 --only handshake

Requires only the standard library.
"""

import argparse
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

DEFAULT_SN = "SIM0000001"

# Deliberately awkward: an apostrophe, a comma, and a non-ASCII character.
# These are the names that break naive string-escaping in the parser.
PEOPLE = [
    ("1001", "Ada O'Brien"),
    ("1002", "Kwame Mensah"),
    ("1003", "Ngozi Okafor-Eze"),
    ("1004", "Jean-Luc Habimana"),
    ("1005", "Smith, John"),
]


def call(base, path, sn, method="GET", body="", params=None, key=None, timeout=30):
    q = {"SN": sn}
    if params:
        q.update(params)
    url = "%s%s?%s" % (base.rstrip("/"), path, urllib.parse.urlencode(q))

    data = body.encode("utf-8") if method == "POST" else None
    req = urllib.request.Request(url, data=data, method=method)
    # This is what the real device sends. Content-Type matters: it decides
    # which shape the body arrives in on the n8n side.
    req.add_header("User-Agent", "iClock Proxy/1.09")
    if method == "POST":
        req.add_header("Content-Type", "text/plain")
    if key:
        req.add_header("X-ADMS-Key", key)

    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            text = r.read().decode("utf-8", "replace")
            return r.status, text, time.time() - started
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), time.time() - started
    except Exception as e:
        return 0, "%s: %s" % (type(e).__name__, e), time.time() - started


def show(label, status, text, secs):
    ok = 200 <= status < 300
    mark = "  ok  " if ok else " FAIL "
    print("%s %-22s %3s  %5.2fs  %s" % (
        mark, label, status or "---", secs,
        text.strip().replace("\r\n", " | ")[:160] or "(empty)"))
    return ok


def do_handshake(base, sn, key):
    status, text, secs = call(
        base, "/iclock/cdata", sn,
        params={"options": "all", "pushver": "2.4.1", "language": "69"}, key=key)
    ok = show("GET cdata (handshake)", status, text, secs)
    if not ok:
        return False
    # The single most common misconfiguration: a server that answers "OK" here
    # leaves the device permanently idle, and it looks like a device fault.
    if text.strip().upper() == "OK":
        print("\n  !! The server answered 'OK' to the handshake.")
        print("     A real terminal reads that as 'nothing to send' and will")
        print("     never push a single punch. It must return a GET OPTION block.\n")
        return False
    if "GET OPTION FROM" not in text:
        print("     warning: response is missing the 'GET OPTION FROM:' header line")
    for flag in ("Realtime=1", "TransFlag"):
        if flag not in text:
            print("     warning: handshake response is missing %s" % flag)
    return True


def do_userinfo(base, sn, key):
    body = "\r\n".join(
        "PIN=%s\tName=%s\tPri=0\tPasswd=\tCard=%s\tGrp=1\tTZ=0000\tVerify=0"
        % (pin, name, 1000000 + int(pin))
        for pin, name in PEOPLE)
    status, text, secs = call(base, "/iclock/cdata", sn, "POST", body,
                              {"table": "USERINFO", "Stamp": "9999"}, key)
    return show("POST USERINFO x%d" % len(PEOPLE), status, text, secs)


def do_biodata(base, sn, key):
    # Not a real template - just base64 of the right rough size, so the column
    # width and the escaping path get exercised.
    import base64
    lines = []
    for pin, _ in PEOPLE:
        blob = base64.b64encode(bytes(random.getrandbits(8) for _ in range(512))).decode()
        lines.append(
            "Pin=%s\tNo=0\tIndex=%d\tValid=1\tDuress=0\tType=1\t"
            "MajorVer=10\tMinorVer=0\tFormat=0\tTmp=%s" % (pin, random.randint(0, 9), blob))
    status, text, secs = call(base, "/iclock/cdata", sn, "POST", "\r\n".join(lines),
                              {"table": "BIODATA", "Stamp": "9999"}, key)
    return show("POST BIODATA x%d" % len(PEOPLE), status, text, secs)


def do_operlog(base, sn, key):
    """The mixed OPERLOG shape many firmwares use instead of USERINFO/BIODATA."""
    body = "\r\n".join([
        "USER PIN=2001\tName=Test Operlog\tPri=0\tCard=0099887",
        "FP PIN=2001\tFID=2\tSize=1024\tValid=1\tTMP=QUJDREVGRw==",
        "OPLOG 4\t1\t0\t0\t%s\t0" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    ])
    status, text, secs = call(base, "/iclock/cdata", sn, "POST", body,
                              {"table": "OPERLOG", "Stamp": "9999"}, key)
    return show("POST OPERLOG (mixed)", status, text, secs)


def do_attlog(base, sn, key, count, dup):
    now = datetime.now().replace(microsecond=0)
    lines = []
    for i in range(count):
        pin, _ = random.choice(PEOPLE)
        ts = (now - timedelta(minutes=random.randint(0, 240))).strftime("%Y-%m-%d %H:%M:%S")
        lines.append("%s\t%s\t0\t%d\t\t0\t0" % (pin, ts, random.choice([1, 1, 1, 4, 15])))
    if dup and lines:
        # Devices really do re-send. If the MERGE key is wrong this shows up
        # as a duplicate row in the dashboard rather than a silent no-op.
        lines.append(lines[0])
    status, text, secs = call(base, "/iclock/cdata", sn, "POST", "\r\n".join(lines),
                              {"table": "ATTLOG", "Stamp": "9999"}, key)
    return show("POST ATTLOG x%d%s" % (count, " (+1 dup)" if dup else ""), status, text, secs)


def do_getrequest(base, sn, key):
    status, text, secs = call(base, "/iclock/getrequest", sn, key=key)
    return show("GET getrequest", status, text, secs)


STEPS = {
    "handshake":  lambda a: do_handshake(a.base, a.sn, a.key),
    "getrequest": lambda a: do_getrequest(a.base, a.sn, a.key),
    "userinfo":   lambda a: do_userinfo(a.base, a.sn, a.key),
    "biodata":    lambda a: do_biodata(a.base, a.sn, a.key),
    "operlog":    lambda a: do_operlog(a.base, a.sn, a.key),
    "attlog":     lambda a: do_attlog(a.base, a.sn, a.key, a.punches, not a.no_dup),
}
ORDER = ["handshake", "getrequest", "userinfo", "biodata", "operlog", "attlog"]


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", required=True,
                   help="Base URL, e.g. http://192.168.1.50:8080")
    p.add_argument("--sn", default=DEFAULT_SN, help="Device serial number")
    p.add_argument("--key", default=None, help="X-ADMS-Key shared secret, if required")
    p.add_argument("--punches", type=int, default=8, help="ATTLOG records to send")
    p.add_argument("--no-dup", action="store_true",
                   help="Do not append a duplicate punch (dedup is tested by default)")
    p.add_argument("--only", choices=ORDER, help="Run a single step")
    p.add_argument("--loop", type=int, metavar="SECONDS",
                   help="Keep pushing punches every N seconds, like Realtime=1")
    args = p.parse_args()

    print("\nSimulating ZKTeco Horus H1  SN=%s  ->  %s\n" % (args.sn, args.base))

    steps = [args.only] if args.only else ORDER
    results = [(name, STEPS[name](args)) for name in steps]

    passed = sum(1 for _, ok in results if ok)
    print("\n%d/%d steps succeeded\n" % (passed, len(results)))

    if args.loop:
        print("Looping every %ds - Ctrl+C to stop\n" % args.loop)
        try:
            while True:
                time.sleep(args.loop)
                do_attlog(args.base, args.sn, args.key, 1, False)
        except KeyboardInterrupt:
            print("\nstopped\n")

    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
