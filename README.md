# osheeep-server

Spring Boot backend for Osheeep.

## Requirements

- JDK 21
- Maven 3.9+

## Local Verification

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```

The default test profile does not connect to MySQL, Redis, or RabbitMQ.
