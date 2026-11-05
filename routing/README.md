# Getting the terminal to n8n without a relay box

## The gap

The device sends to a path burned into its firmware:

```
http://<whatever you configure>/iclock/cdata
```

n8n serves its webhooks at:

```
https://automations.example.org/webhook/iclock/cdata
```

Two mismatches: the `/webhook` path prefix, and HTTP vs HTTPS.

The original design bridged both with an nginx relay on a Linux box on the LAN.
You don't have one and can't get one, so that's gone. Below are the three ways
to close the gap without it, cheapest first. **Test them in this order** — each
takes minutes and the first one that works ends the exercise.

There is no code to write for any of these. This is a configuration problem.

---

## Option 1 — the device accepts a path (free, 2 minutes, test first)

The Horus H1 is Android-based, and a good number of Android-era ZK terminals
let you put a path — sometimes a whole URL — in the Cloud Server field, rather
than just a bare IP. The older embedded units don't. Yours might.

On the terminal: **Menu → Comm → Cloud Server Setting**, and try:

| Field | Value |
|---|---|
| Server Address | `automations.example.org/webhook` |
| Server Port | `443` |
| HTTPS / SSL | **On** |

If the field rejects the slash, or truncates at the first `/`, this option is
out — move to Option 2. If it accepts it, watch the n8n **Executions** tab for
a hit. That's the whole job done: no proxy change, no hardware, nothing.

Some firmware exposes this as a separate **Server Name** or **Path** field
alongside the address. Worth a look through the whole menu before giving up.

---

## Option 2 — one rewrite rule on the edge proxy (best outcome, one request)

`automations.example.org` already sits behind a reverse proxy — the same
one doing OAuth. Whoever administers it adds a rule that rewrites `/iclock/*`
to `/webhook/iclock/*`, and the device can then be pointed straight at the
hostname with no path at all.

This is a small ask, and here is the argument to give them: **`/webhook/*` is
already exempt from the OAuth redirect on that host.** A request to
`/webhook/iclock/cdata` returns `404` (n8n saying "no such workflow"), not the
`303 → /oauth/authorize` that `/healthz` and `/api/v1/*` return. So the bypass
rule already exists; this only extends it to one more prefix.

The config snippet to hand them is in [`iclock-rewrite.conf`](iclock-rewrite.conf).

Then on the terminal:

| Field | Value |
|---|---|
| Server Address | `automations.example.org` |
| Server Port | `443` |
| HTTPS / SSL | On |

---

## Option 3 — a small always-on host (only if 1 and 2 both fail)

If the proxy team won't add the rule *and* the device can't take a path, then
something has to sit in the middle. It does not have to be an office server:

- **A cloud VM.** The cheapest tier at any provider is a few dollars a month
  and is plenty — this is a path rewrite, not a workload. It needs to be
  reachable from wherever the terminal has internet access.
- **A Raspberry Pi** on the office network, if "no Linux machine" was about
  procurement of a server rather than about hardware in general.

Either way the config is `iclock-rewrite.conf`, pointed at n8n as the upstream.

**Not your laptop.** You need this around the clock including weekends, and a
laptop that sleeps, moves, and changes IP address cannot do that job.

---

## The one thing that could sink all three

**Does the Horus H1 speak HTTPS?** Options 1 and 2 both require it, because
`automations.example.org` is HTTPS-only.

Android-based ZK terminals usually manage it. Older embedded ones either can't,
or ship a TLS stack too old for a modern proxy to accept — and the failure is
silent: the device just never appears in the logs.

Find out early. Phase 1 of the walkthrough has you point the terminal at a
plain-HTTP listener first, which proves the device works *before* TLS is in the
picture. If plain HTTP works and HTTPS doesn't, you've isolated it to TLS and
Option 3 becomes mandatory — a middlebox terminating plain HTTP on the device
side is then the only way through.

---

## Whichever option you land on: the endpoint is public

`automations.example.org/webhook/*` is reachable from the open internet
with no login. Once the workflow is active, anyone who guesses that URL can
inject attendance records or pull fingerprint templates.

The terminal cannot send a secret — that's why the original design put the
shared secret on the relay. Without a relay you lose that, so use what n8n
gives you instead, on every Webhook node:

- **Options → IP(s) Allowlist** — set to your office's public egress IP. This
  is the important one. Get the address from whoever runs the network, or from
  `curl ifconfig.me` on any machine on that network.

If you end up on Option 3, put Header Auth back as well — the middlebox can
attach the secret the device can't.
