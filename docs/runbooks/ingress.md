# Runbook: the ingress

Sprint 33 (decisions in [ADR-0286](../adr/0286-deployment-topology-the-ingress-and-the-forwarded-headers.md)). The host name is the
tenant, so these rules are a security control. Proved against the rehearsal by `./checks.ps1`.

## When to use this

Before changing anything about routing, certificates, headers, size limits or time limits; and to find out why a request was
refused before it reached the application.

## The rules

1. **Preserve `Host`.** The tenant is taken from it. The balancer keeps it as the caller wrote it (a setting of the balancer);
   never rewrite it.
2. **`PLATFORM_TRUST_FORWARDED_HOST` is false** behind the cloud balancer: it never sets `X-Forwarded-Host` and passes a client's
   through. The edge sidecar also deletes it.
3. **`X-Forwarded-For` is one honest address.** The balancer appends; the edge sidecar takes the first address outside the
   balancer's network and writes it as the only value; the application, told `PLATFORM_FORWARDED_FOR_HOPS=1`, takes the right-most
   entry anyway. A caller's own entries never count.
4. **https only.** The wildcard certificate covers the platform domain and every name under it; port 80 answers a redirect; HSTS
   for a year with subdomains.
5. **The management port is never routed.** 8081 is opened to nobody (not even the balancer); the one health path the balancer uses
   is the sidecar's `/edge-ready`; `/actuator` is answered 404 by a rule of the balancer and by the sidecar.
6. **Size and time limits per kind of request** (megabytes here are decimal, 1,000,000 bytes, as the proxy reads them; each is above the application's own limit) (`deploy/edge/api-rules.caddy`; they also hold for a body with no announced length):

   | Kind | Body | Time |
   |------|------|------|
   | file on a record | 27 MB | 120 s |
   | spreadsheet upload | 6 MB | 60 s |
   | imports (resumable pieces) | 30 MB | 120 s |
   | bulk (internal and public) | 9 MB | 60 s |
   | inbound receiver of an external system | 2 MB | 15 s |
   | everything else under `/api` (records, public API, a sheet grid) | 2 MB | 30 s |

   The balancer's idle timeout (130 s) is longer than the longest. The application stays the authority (it refuses at its own
   limits in its own words); change a limit here and in the application's setting together.
7. **Nothing is cached or buffered** that must not be: the API's answers carry their own cache headers.

## Checking it *(cloud and rehearsal)*

`curl -sI https://<platform domain>/` shows HSTS; `curl -sI http://<platform domain>/` is a redirect; `curl -i https://<platform
domain>/actuator/health` is 404; a request to an organization host with `X-Forwarded-Host` naming another organization still answers
for the host in `Host`; an oversized body is 413. The rehearsal's `./checks.ps1` does all of these and the 75-attempt test that a
forged `X-Forwarded-For` does not escape the per-source sign-in limit.

## How I know it worked

`./checks.ps1` prints `RESULT PASS`; in a deployment the same `curl` checks give the answers above and `balancer-5xx` stays quiet.
