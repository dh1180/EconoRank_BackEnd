# StatAgora Backend

Spring Boot 3.5 / Java 17 API for a global economic rankings and discussion MVP. The frontend lives in [StatAgora_FrontEnd](https://github.com/dh1180/StatAgora_FrontEnd).

## Run locally

Java 17+ is required; Maven is downloaded by the checked-in wrapper.

```sh
./mvnw spring-boot:run
# Windows: .\mvnw.cmd spring-boot:run
```

The API runs at `http://localhost:8080/api`. Default development storage is a persistent H2 file under ignored `data/`. The database is migrated with Flyway and loads the bundled genuine World Bank snapshot once per release. No account, credential, or fabricated discussion is seeded.

Start the sibling Next.js checkout with `npm ci && npm run dev`, then open `http://localhost:3000/en` or `/ko`. Browser requests use the frontend's same-origin `/api` proxy; cookies and CSRF work without cross-origin credentials or local storage tokens.

### PostgreSQL

Set environment variables before starting:

```text
DATABASE_URL=jdbc:postgresql://localhost:5432/statagora
DATABASE_USERNAME=statagora
DATABASE_PASSWORD=<your-password>
COOKIE_SECURE=true   # use false only for local HTTP
```

Create the database first. The same migrations support PostgreSQL and H2. For both repositories plus PostgreSQL, clone the repositories as sibling directories named `StatAgora_BackEnd` and `StatAgora_FrontEnd`, copy `.env.example` to `.env`, set a local database password, and run `docker compose up --build` here. Services bind to loopback. Put an HTTPS reverse proxy in front of the frontend for deployment; keep PostgreSQL and the backend private. `API_BASE_URL` for the frontend rewrite is also a build-time setting, so rebuild when its upstream changes.

## Economic data

The committed snapshot contains 1,732 non-null observations for 2022–2024, fetched on 2026-11-22 from the World Bank Indicators v2 API. The World Bank classifies some territories as separate economies. Regional/income/world aggregates are excluded using country metadata (`region.id != NA`), not a hard-coded list of major countries.

| API indicator | World Bank series | Unit |
|---|---|---|
| `gdp` | `NY.GDP.MKTP.CD` | Current US dollars |
| `ppp` | `NY.GDP.MKTP.PP.CD` | Current international dollars |
| `exports` | `NE.EXP.GNFS.CD` | Current US dollars, **goods and services** |

Ranks use SQL `RANK()` descending, with country code for deterministic tie ordering. Every row includes reference year, source URL, and retrieval time. Missing values remain absent; countries with no value for a requested metric/year are not ranked. Comparisons do not substitute another year. This is a reviewed historical snapshot, not a live quote or IMF forecast.

### Refresh from the real upstream API

```sh
python scripts/import_world_bank.py --from-year 2022 --to-year 2024
./mvnw verify
```

Python 3 standard library only. The importer validates responses and coverage, retries upstream failures, filters aggregates and null values, and atomically replaces the JSONL file only after all three series succeed. Review the data diff, commit it, rebuild, and restart. Startup imports a new retrieval release transactionally, replacing its metric/year partitions (including removed observations); the same release is skipped on subsequent starts. Existing members/discussions are preserved. Failed import rolls back. Do not start multiple instances while running a data migration. For future automated ingestion, move the reviewed-release workflow into a scheduled worker with locking and publication approval. See [World Bank API documentation](https://datahelpdesk.worldbank.org/knowledgebase/articles/898581-api-basic-call-structures) and [data terms](https://www.worldbank.org/en/about/legal/terms-of-use-for-datasets).

## API contract

Responses are JSON; errors use `{ "message": "..." }`. Lists are zero-based and bounded (`page=0..10000`, `size=1..100`). Unknown countries/resources return 404, invalid inputs 400, duplicate email/report/block 409, unauthenticated mutations 401, missing/invalid CSRF or admin role 403, and throttled actions 429.

| Method | Path under `/api` | Behavior / access |
|---|---|---|
| GET | `/health` | Database readiness |
| GET | `/indicators`, `/years`, `/countries` | Public metadata |
| GET | `/rankings?indicator=gdp&year=2024&page=0&size=25` | Public rankings `{items,page,size,total,indicator,year,source}` |
| GET | `/countries/KOR` | Country plus all available observations |
| GET | `/compare?countries=USA,KOR,JPN&year=2024` | 2–5 distinct ISO3-style World Bank codes; returns available observations only |
| GET | `/auth/csrf` | `{token,headerName}`; creates CSRF session |
| POST | `/auth/register` | `{email,password,displayName}`; creates member and signs in |
| POST | `/auth/login` | `{email,password}`; signs in |
| GET | `/auth/me` | `{id,displayName,role}` or 401 |
| POST | `/auth/logout` | Invalidates session |
| GET | `/posts?space=global&page=0&size=20` | Public feed; signed-in user's blocked authors excluded |
| POST | `/posts` | Member: `{spaceId,title,body,language}`; returns `{id}` |
| GET | `/posts/{id}` | Visible post plus first 100 visible comments |
| POST | `/posts/{id}/comments` | Member: `{body}` |
| DELETE | `/posts/{id}`, `/comments/{id}` | Author only; soft deletion |
| POST | `/reports` | Member: `{targetType:"post" or "comment",targetId,reason}` |
| GET, POST, DELETE | `/blocks`, `/blocks/{memberId}`, `/blocks/{memberId}` | List, add, remove personal author block |
| GET | `/admin/reports?page=0` | Admin: 50 reports per page including reported content and parent post ID |
| POST | `/admin/hide` | Admin: `{targetType,targetId}`; hide, resolve reports, write audit event |

Spaces are `global` or `country-KOR` (etc.). Post title/body limits are 160/10,000 characters; comment 3,000; report reason 500; language `en` or `ko`. All input is parameterized and validated. Content is plain text; clients must escape it, never render it as HTML.

### Authentication from another client

Keep the `JSESSIONID` cookie. Before **each mutation**, GET `/auth/csrf`, then send the returned token in the returned header name together with that cookie. Register/login rotate the session ID and invalidate the old CSRF token. The Next.js client handles this automatically. Passwords are BCrypt cost 12; registration requires 10–64 characters and at most 72 UTF-8 bytes. No passwords/hashes/emails are returned in public author objects.

Administrator access is never granted through registration. An operator can promote a verified account with a parameterized database operation equivalent to `UPDATE member SET role='ADMIN' WHERE email='<verified-email>';`. Log out and log in again afterward. The frontend displays the moderation panel only for admins, and the API enforces the role independently.

## Model and extension points

`country → observation ← indicator`, with `dataset_release` providing provenance. Community: `member → post → comment`, `discussion_space` links a country or the global board. Reports, blocks, and moderation audits are separate tables. Post/comment creation and an outbox-style `community_event` are committed in the same transaction. A future publisher can deliver these events to a broker/WebSocket service; chat messages should be a separate bounded aggregate sharing spaces, membership, blocking, and moderation rules. No chat endpoint is exposed yet.

## Checks

```sh
./mvnw verify
# PostgreSQL: set TEST_DATABASE_URL, TEST_DATABASE_USERNAME, TEST_DATABASE_PASSWORD
# to an isolated test database, then run the same command.
```

Integration coverage includes provenance and aggregate exclusion, ordering, invalid pagination/filters, compare, hashed credentials, real CSRF headers, session logout, ownership, comments, duplicate reports, admin authorization/hiding, blocked authors, deletion, and throttling. GitHub Actions runs the suite against both H2 and PostgreSQL. Local verification passed against H2 and PostgreSQL 18; Docker execution was not available in the development environment.

## MVP operating boundaries

Sessions and bounded request throttles are in memory: run a single backend instance for this MVP. Behind the Next.js proxy, IP throttles can be shared by users of that proxy; configure a trusted gateway/shared Redis limiter before public growth. A restart signs users out. Posts, comments, members and reports remain in the database. Authenticated posts: 5/minute; comments and reports: 10/minute; login: 10/minute per observed peer; registration: 5/hour per peer.

Email verification, password recovery, member suspensions, automated spam classification, report dismissal/restoration, more than 100 replies per thread, live chat, automatic translation, company data, and advertising are subsequent work. Establish actual moderation coverage and account recovery before an unrestricted public launch. HTTPS and `COOKIE_SECURE=true` are required for deployment; no hosting deployment is included in this PR.
