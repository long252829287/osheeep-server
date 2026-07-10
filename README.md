# osheeep-server

Spring Boot backend for Osheeep.

## Requirements

- JDK 21
- Maven 3.9+
- Local `.env.local` configured from `.env.example`

## Start Locally

```bash
cd /Users/longlonglong/Developer/Personal/Apps/osheeep/osheeep-server
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
set -a
source .env.local
set +a
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The application listens on `http://localhost:8080` and uses the development server for MySQL, Redis, and RabbitMQ.

## Local Tools

- Health check: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Swagger UI supports JWT authorization. Register or log in, select **Authorize**, and paste the `accessToken` value without the `Bearer ` prefix before invoking protected endpoints.

## Frontend Proxy

`frame-work` proxies `/api` to `http://localhost:8080` during Vite development. Start the backend before using the thought-brewing pages.

## Tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```

The default test profile does not connect to MySQL, Redis, or RabbitMQ.
