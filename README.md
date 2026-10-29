# VCRD Backend

Vendor Invoice Management Portal - Spring Boot backend.

## Prerequisites
- JDK 21 (e.g. Eclipse Temurin), Maven 3.9+
- No database install needed for local dev (embedded H2). PostgreSQL optional.

## Run
```
mvn spring-boot:run                                   # H2 profile (default), data in ./data
mvn spring-boot:run -Dspring-boot.run.profiles=postgres   # PostgreSQL (DB_URL / DB_USERNAME / DB_PASSWORD)
mvn test
```
- Swagger UI: http://localhost:8080/swagger-ui.html
- H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/vcrd-db`, user `sa`)

## Demo users (password `Password@123`)
| User | Role | Sites |
|---|---|---|
| site.user | SITE_USER | SITE-01 |
| site2.user | SITE_USER | SITE-02 |
| pmo.user | PMO | all |
| approver | APPROVER | all |
| admin | PORTAL_ADMIN | all |
| auditor | AUDITOR | all |

Mock SAP POs: `4500012345` (SITE-01), `4500012346` (SITE-02), `4500012347` (SITE-01, not released).

## Try it
1. `POST /api/v1/auth/login` → copy `accessToken`, click **Authorize** in Swagger.
2. `GET /api/v1/pos/4500012345`
3. `POST /api/v1/invoices` → `POST /api/v1/invoices/{id}/documents` (PDF/PNG/JPG)
4. `POST /api/v1/invoices/{id}/actions/SUBMIT` → `VERIFY` (site.user) → system validation → `INITIATE_ZPARK` (pmo.user) → SAP / GR outcomes → `APPROVE` (approver) → `POST_MIRO` (pmo.user) → clearing. Full lifecycle: `TransitionTable`
5. `GET /api/v1/invoices/{id}/history`, `GET /api/v1/audit?entityId={id}` (auditor)

## Structure
| Package | Purpose |
|---|---|
| `security` | JWT login (interim until SSO), current user, method security |
| `identity` | Users, roles, site scopes, dev seed data |
| `po` | PO lookup + local cache; `po.sap.SapPoGateway` is the SAP boundary (mock today) |
| `invoice` | Invoices, lines, documents, file storage, duplicate checks |
| `workflow` | Status transitions (`TransitionTable`), guards, history |
| `audit` | Append-only audit events |
| `common` | Errors (RFC 7807 ProblemDetail), correlation ID |

## Deliberately deferred (waiting on business/SAP decisions)
- Real SAP gateway, JACK PARK, MIGO/MIRO, reconciliation
- Extraction (OCR), validation rule engine, exception module
- Final workflow sequence (`TransitionTable` is interim), notifications, reports, SSO
