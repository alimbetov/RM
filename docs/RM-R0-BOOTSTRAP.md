# RM-R0 — Bootstrap

## Stack
Java 21, Spring Boot 4.1.3, Maven, Spring MVC, JPA/Hibernate, Lombok, H2 (dev smoke), PostgreSQL (test development), Liquibase formatted SQL, JUnit.

## Run
```bash
mvn clean test
mvn spring-boot:run
curl http://localhost:8080/api/v1/health
```
Expected: `{"status":"UP","application":"RM"}`.

## H2 (dev only)
- JDBC URL: `jdbc:h2:mem:rm;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`
- Username: `sa`, password blank
- Console: `http://localhost:8080/h2-console`
- In-memory data is lost on application shutdown. Liquibase executes the master SQL changelog when the app starts and records applied changes in DATABASECHANGELOG and DATABASECHANGELOGLOCK.

## Active profiles: dev and test

- `dev` (default): H2 in-memory, suitable for local smoke tests.
- `test`: PostgreSQL `rm_test`, used for ongoing development and integration verification.
- Both use Liquibase SQL-only migrations and Hibernate `ddl-auto=validate`.
- Never enable H2 console in PostgreSQL profile; never point test at production credentials.

Run dev:
```bash
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
curl http://localhost:8080/api/v1/health
```

Run PostgreSQL test profile after creating a disposable DB and least-privilege user:
```bash
export RM_TEST_DB_URL=jdbc:postgresql://localhost:5432/rm_test
export RM_TEST_DB_USERNAME=rm
export RM_TEST_DB_PASSWORD=... # secure environment
SPRING_PROFILES_ACTIVE=test mvn spring-boot:run
```

`mvn test` without overriding profile remains H2-based. To test the PostgreSQL profile explicitly, set `SPRING_PROFILES_ACTIVE=test` and provide reachable PostgreSQL credentials. For CI add a distinct PostgreSQL Testcontainers suite; H2 passing does not qualify PostgreSQL-specific SQL, locking or partitioning.

## Architecture decisions
- One Spring Boot application with modules under `kz.rm`.
- No CRM tables, KMS or crypto keys in the bootstrap milestone.
- Do not add microservices, Redis or partitioned tables.
- All migrations use **Liquibase formatted SQL**. No Flyway, XML or YAML migration changelogs.
- The OSS-compatible root is `src/main/resources/db/changelog/db.changelog-master.sql`; append new uniquely identified `--changeset` blocks, never edit already executed changesets.
- For a later multi-file SQL layout, check whether the available Liquibase edition supports SQL `--include`; do not silently introduce XML or YAML.
- Hibernate uses `ddl-auto=validate` in all profiles. Liquibase owns schema changes.
