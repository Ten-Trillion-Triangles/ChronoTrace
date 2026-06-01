# Security

## Reporting a vulnerability

Please report security issues to **security@chronotrace.example.com** (replace with the real address before publishing). Do not file a public issue.

We aim to:
- Acknowledge receipt within 3 business days.
- Provide a triage and severity assessment within 7 business days.
- Ship a fix or mitigation within 30 days for high-severity issues, 90 days for medium.

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | Yes |
| < 1.0 | No |

## Security model

ChronoTrace is a logging/observability system, not a security boundary. The server should be deployed behind a trusted network or with `CHRONOTRACE_AUTH_MODE=apiKey` or `bearer` enabled. Default `authMode=none` is intended for local development only.

API keys (when enabled) are validated at `chronotrace-server/.../ServerModule.kt:823-876`. Bearer tokens are validated the same way. mTLS is a documented option in the `AuthConfig` union but is **not yet implemented at the network layer** — selecting mTLS in `sdk-ts` results in no auth header being sent. Do not rely on mTLS for production.

## Disclosure timeline

We follow coordinated disclosure. After a fix is shipped, we publish a CVE and a release note. We credit reporters unless they request anonymity.
