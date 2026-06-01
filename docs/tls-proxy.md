# TLS/HTTPS Proxy Requirement — ChronoTrace Production

> Status: Production
> Last updated: 2026-05-28

## Requirement

**ChronoTrace server itself does not terminate TLS.** For production deployments, a TLS termination proxy (reverse proxy) **must** be placed in front of the ChronoTrace server. All external client traffic — and all communication from SDK clients to the server — must flow over HTTPS.

This is a deliberate architectural choice: ChronoTrace uses a minimal embedded HTTP server (ktor-netty) and delegates TLS termination to a battle-hardened reverse proxy (Caddy, nginx, Traefik, or an AWS ALB) that handles certificate rotation, OCSP stapling, and protocol negotiation.

---

## Architecture

```
SDK Client (HTTPS) --> [TLS Proxy] (terminates TLS) --> ChronoTrace Server (HTTP:8080)
```

The TLS proxy terminates TLS on port 443 and forwards plain HTTP to the ChronoTrace server on port 8080. The ChronoTrace server must only be reachable from the proxy — never exposed directly to the internet.

---

## Server Configuration

ChronoTrace reads TLS configuration from environment variables. However, note that these configure **mutual TLS (mTLS) client certificates**, not server-side TLS termination:

| Variable | Description |
|---|---|
| `TLS_KEYSTORE_PATH` | Path to JKS keystore for mTLS client auth |
| `TLS_KEYSTORE_PASSWORD` | Keystore password |
| `TLS_KEY_ALIAS` | Key alias within the keystore (default: `chronotrace`) |
| `TLS_TRUSTSTORE_PATH` | Path to JKS truststore for mTLS client auth |
| `TLS_TRUSTSTORE_PASSWORD` | Truststore password |

When `TLS_KEYSTORE_PATH` and `TLS_KEYSTORE_PASSWORD` are **both** set, the server enforces client certificates (mutual TLS). When they are not set, the server runs in plain HTTP mode and relies on the fronting proxy for transport security.

**To require client certificates in production**, set the truststore variables so the server can validate incoming client certificates.

---

## Proxy Configuration Examples

### Caddy (Recommended)

Caddy is recommended for most deployments due to automatic HTTPS, automatic certificate management, and minimal configuration.

```Caddyfile
# Caddyfile
https://api.chronotrace.example.com {
    reverse_proxy localhost:8080
}
```

For environments where Caddy cannot manage DNS (e.g., Kubernetes), use the DNS provider plugin:

```Caddyfile
https://api.chronotrace.example.com {
    reverse_proxy localhost:8080
    tls {
        dns cloudflare {env.CF_API_TOKEN}
    }
}
```

### nginx

```nginx
server {
    listen 443 ssl;
    server_name api.chronotrace.example.com;

    ssl_certificate     /etc/ssl/certs/server.crt;
    ssl_certificate_key /etc/ssl/private/server.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Traefik (Docker Compose)

```yaml
services:
  traefik:
    image: traefik:v3.0
    ports:
      - "443:443"
      - "80:80"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./traefik.yaml:/traefik.yaml:ro
      - ./certs:/certs:ro
    command:
      - --configFile=/traefik.yaml

  chronotrace:
    image: chronotrace/server:latest
    expose:
      - "8080"
```

```yaml
# traefik.yaml
entryPoints:
  websecure:
    address: ":443"
    http:
      tls:
        certResolver: letsencrypt

providers:
  docker:
    exposedByDefault: false

http:
  routers:
    chronotrace:
      rule: "Host(`api.chronotrace.example.com`)"
      service: chronotrace
      entryPoints:
        - websecure
      tls:
        certResolver: letsencrypt

  services:
    chronotrace:
      loadBalancer:
        servers:
          - url: http://chronotrace:8080
```

---

## Kubernetes Deployment

In Kubernetes, use an Ingress controller (such as ingress-nginx, Traefik, or AWS ALB) for TLS termination. The ChronoTrace Service should be of type `ClusterIP` and not directly exposed.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: chronotrace
  annotations:
    kubernetes.io/ingress.class: nginx
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  tls:
    - hosts:
        - api.chronotrace.example.com
      secretName: chronotrace-tls
  rules:
    - host: api.chronotrace.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: chronotrace
                port:
                  number: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: chronotrace
spec:
  type: ClusterIP
  ports:
    - port: 8080
      targetPort: 8080
  selector:
    app: chronotrace
```

---

## Certificate Management

### Certificate Rotation

When using Caddy, certificates are automatically renewed before expiry. For nginx and Traefik, implement a certificate reload mechanism:

- **nginx + certbot**: Use `nginx -s reload` after certificate renewal
- **Traefik**: Enable the `tls` challenge or use the file provider with inotify
- **AWS ALB**: Certificates are managed by AWS Certificate Manager and automatically renewed

### Mutual TLS (mTLS)

If your environment requires mTLS (service mesh, zero-trust networks), configure the TLS proxy to forward client certificates to ChronoTrace:

```nginx
proxy_set_header X-Forwarded-Client-Cert $http_x_forwarded_client_cert;
```

The ChronoTrace server can then validate client certificates using the JKS truststore configured via `TLS_TRUSTSTORE_PATH`.

---

## Security Considerations

1. **Never expose ChronoTrace HTTP port directly** — The server binds to `127.0.0.1:8080` by default, ensuring it is only reachable from the local proxy. If binding to all interfaces is required for container networking, use a network policy (Kubernetes) or firewall rule (VM) to restrict access to the proxy IP only.

2. **X-Forwarded-Proto header** — ChronoTrace respects the `X-Forwarded-Proto` header set by the proxy. If the proxy terminates TLS and sets `X-Forwarded-Proto: https`, the server correctly reports the connection as secure.

3. **API key security** — API keys are transmitted in the `Authorization` header. HTTPS ensures these credentials are encrypted in transit. Never transmit API keys over plain HTTP.

4. **ClickHouse and Valkey connections** — For production, ensure ClickHouse and Valkey are also on private networks or use TLS. The default docker-compose setup in ChronoTrace uses plain connections on a private Docker network, which is acceptable for single-host deployments. For multi-host or cloud deployments, enable ClickHouse HTTPS and Valkey TLS.

---

## Compliance Note

If your deployment must satisfy compliance requirements (SOC 2, HIPAA, PCI-DSS), TLS 1.2+ is required. Configure your proxy to enforce minimum TLS 1.2 with strong cipher suites. The example nginx configuration above meets this requirement.

---

## Quick Start

To stand up a local development environment with TLS using Caddy:

```bash
# 1. Start ChronoTrace (HTTP mode is fine for local dev)
CHRONOTRACE_STORAGE_MODE=file ./gradlew :chronotrace-server:run &

# 2. Run Caddy as a reverse proxy
echo 'localhost:8443 {
    reverse_proxy localhost:8080
}' > Caddyfile

caddy run
```

Clients can then connect to `https://localhost:8443`.