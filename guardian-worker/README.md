# SENE GUARDIAN Worker (staging scaffold)

This is source code only. It is not deployed and contains no credentials or live business data.

## Routes
- GET `/v1/health`: public liveness response; no business information.
- POST `/v1/snapshot`: ingest-only, requires `INGEST_TOKEN`, accepts a strict aggregate allowlist, 32 KB maximum.
- GET `/v1/guardian/business-overview?installationId=...`: read-only, requires a separate `MUSE_READ_TOKEN`.

## Required Cloudflare setup (not performed)
1. Create a KV namespace and replace the placeholder ID in wrangler.toml.
2. Configure Worker secrets `INGEST_TOKEN` and `MUSE_READ_TOKEN` independently (random values of at least 32 characters).
3. Review account Free plan quotas and data-retention/privacy terms before deployment.
4. Add origin-side rate limiting and a per-installation authorization/revocation registry before production.
5. Confirm the exact Muse connector protocol and authentication requirements. This scaffold is not yet an MCP server.

## Security boundary
Only aggregated inventory counts and business status are accepted. No employee names, phone numbers, payroll, attendance events, report bodies, product codes, prices, credentials, license data, or SENE-VENTAS pairing codes are part of this schema. No write route exists for business records.

The installation ID is an independent random identifier; never derive it from a commercial pairing or license code. KV data expires after 35 days. The latest snapshot is overwritten, not appended.

## Local checks
Use Wrangler's local dev/test workflow and add schema, auth, stale-snapshot, oversized-payload, cross-installation and revocation tests before deployment. No deployment command is included intentionally.
