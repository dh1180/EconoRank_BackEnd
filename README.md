# EconoRank Backend

Spring Boot 3.5 / Java 17 API powering **EconoRank** (`econorank.com`) — global economic rankings (IMF WEO), real-time global company market cap rankings, and a multilingual global economic community.

## Key Features
- **IMF WEO Macro Indicators (`gdp`, `gdppc`, `ppp`)**: Official IMF World Economic Outlook data (Nominal GDP, GDP per capita, PPP GDP) with explicit `ACTUAL` vs `ESTIMATE_OR_FORECAST` labels and 24-hour automatic upstream check.
- **Real-Time Global Company Market Cap (`/api/companies`)**: Tracks 11,000+ public companies across 30+ national stock exchanges and 7 world regions (`NORTH_AMERICA`, `ASIA`, `EUROPE`, `MIDDLE_EAST`, `OCEANIA`, `LATIN_AMERICA`, `AFRICA`) using a cost-efficient 15-minute lazy TTL cache.
- **Single Global Community**:
  - Session + CSRF authenticated accounts with permanent country lock at sign-up (`/api/auth/register`, `/api/geo`).
  - Anonymous guest posts & comments (`/api/guest/**`) with IP-country default nicknames (`ㅇㅇ`, `名無し`, `匿名`, `Anon`, etc.) and BCrypt-hashed deletion passwords.
  - Safe live stat card embeds (`[[stat:country:...]]`, `[[stat:company:...]]`).
- **On-Demand Post/Comment Translation (`/api/posts/{id}/translate`, `/api/comments/{id}/translate`)**:
  - Local Docker **LibreTranslate** integration (`docker-compose.libretranslate.yml`) with permanent DB caching (`content_translation`, Flyway V6) and `[[stat:...]]` token preservation.

## Run Locally

Java 17+ is required:

```sh
./mvnw spring-boot:run
# Windows:
.\mvnw.cmd spring-boot:run
```

Optional local translation server (Docker):

```sh
docker compose -f docker-compose.libretranslate.yml up -d
```

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP server port |
| `DATABASE_URL` | `jdbc:h2:file:./data/statagora;...` | PostgreSQL or H2 JDBC URL |
| `DATABASE_USERNAME` | `sa` | Database username |
| `DATABASE_PASSWORD` | _(empty)_ | Database password |
| `COOKIE_SECURE` | `false` | Set `true` behind HTTPS in production |
| `TRANSLATION_URL` | `http://127.0.0.1:5000` | LibreTranslate base URL |
| `TRANSLATION_API_KEY` | _(empty)_ | Optional LibreTranslate API key |

## Verification

```sh
./mvnw test
```

15 automated tests cover IMF workbook parsing, rankings/compare/country endpoints, company market cap parsing, session auth & CSRF, moderation, guest posting, and LibreTranslate token preservation + DB caching.
