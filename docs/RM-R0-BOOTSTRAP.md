# RM-R0 — Bootstrap

## Stack
Java 21, Spring Boot 4.1.3, Maven, Spring MVC, JPA/Hibernate, Lombok, H2 (dev), PostgreSQL driver (future), Liquibase formatted SQL, JUnit.

## Run
```bash
mvn clean test
mvn spring-boot:run
curl http://localhost:8080/api/v1/health
```
Expected: `{"status":"UP","application":"RM"}`.

## H2 (dev only)
- JDBC URL: `jdbc:h2:mem:rm`
- Username: `sa`, password blank
- Console: `http://localhost:8080/h2-console`
- In-memory data is lost on application shutdown. Liquibase executes the master SQL changelog when the app starts and records applied changes in DATABASECHANGELOG and DATABASECHANGELOGLOCK.

## PostgreSQL later
```bash
export RM_DB_URL=jdbc:postgresql://localhost:5432/rm
export RM_DB_USERNAME=rm
export RM_DB_PASSWORD=... # supply via secret manager
SPRING_PROFILES_ACTIVE=postgres mvn spring-boot:run
```
The postgres profile is a **future placeholder**, not deployable until schema migrations are implemented. It sets `ddl-auto=validate` deliberately. Development H2 is not proof of PostgreSQL parity. Verify PostgreSQL integration with Testcontainers before production. Never enable H2 console outside development.

## Architecture decisions
- One Spring Boot application with modules under `kz.rm`.
- No CRM tables, KMS or crypto keys in the bootstrap milestone.
- Do not add microservices, Redis or partitioned tables.
- All migrations use **Liquibase formatted SQL**. No Flyway, XML or YAML migration changelogs.
- The OSS-compatible root is `src/main/resources/db/changelog/db.changelog-master.sql`; append new uniquely identified `--changeset` blocks, never edit already executed changesets.
- For a later multi-file SQL layout, check whether the available Liquibase edition supports SQL `--include`; do not silently introduce XML or YAML.
- Hibernate uses `ddl-auto=validate` in all profiles. Liquibase owns schema changes.
