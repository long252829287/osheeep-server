# Server Dependencies

The development server is `82.156.49.122` and is used by the `local` Spring profile.

## Installed Services

- MySQL Server `8.0.45`
- Redis Server `7.2.7`
- RabbitMQ Server `3.12.11`

The server-side development credentials are stored at `/root/osheeep-dev.env` with `0600` permissions. A local copy is kept in `.env.local`, which is ignored by Git.

## Local Usage

Load the local environment before starting the app:

```bash
cd /Users/longlonglong/Developer/Personal/Apps/osheeep/osheeep-server
set -a
source .env.local
set +a
```

Run the app with the `local` profile:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Check the app health endpoint in another terminal:

```bash
curl http://localhost:8080/actuator/health
```

Open the local API explorer after the application starts:

```text
http://localhost:8080/swagger-ui.html
```

The backend runs on the local Mac. The development server only provides MySQL, Redis, and RabbitMQ; it does not expose the local Swagger UI.

## Connectivity Checks

```bash
nc -vz "$OSHEEEP_DB_HOST" "$OSHEEEP_DB_PORT"
nc -vz "$OSHEEEP_REDIS_HOST" "$OSHEEEP_REDIS_PORT"
nc -vz "$OSHEEEP_RABBITMQ_HOST" "$OSHEEEP_RABBITMQ_PORT"
```

## Server Notes

- MySQL databases: `osheeep_dev`, `osheeep_test`
- MySQL user: `osheeep_dev`
- Redis requires password authentication and listens on `6379`.
- RabbitMQ vhost: `/osheeep`
- RabbitMQ user: `osheeep_dev`
- RabbitMQ management plugin is enabled on `15672`.
- Host-level `firewalld` was inactive during setup. If external connectivity fails, check the cloud security group first.
- `frame-work` proxies `/api` to the locally running backend at `http://localhost:8080`.
