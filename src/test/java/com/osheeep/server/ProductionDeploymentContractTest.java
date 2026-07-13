package com.osheeep.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionDeploymentContractTest {
    private final Path root = Path.of("").toAbsolutePath();

    @Test
    void productionProfileWritesBoundedRollingLogs() throws IOException {
        String yaml = readRequired("src/main/resources/application-prod.yml");

        assertThat(yaml).contains("/opt/osheeep-server/logs/application.log");
        assertThat(yaml).contains("max-file-size: 20MB");
        assertThat(yaml).contains("max-history: 14");
        assertThat(yaml).contains("total-size-cap: 300MB");
    }

    @Test
    void systemdAlwaysRunsTheFixedJarAsOsheeep() throws IOException {
        String unit = readRequired("deploy/production/osheeep-server.service");

        assertThat(unit).contains("User=osheeep");
        assertThat(unit).contains("EnvironmentFile=/opt/osheeep-server/osheeep-server.env");
        assertThat(unit).contains("/opt/osheeep-server/osheeep-server.jar");
        assertThat(unit).contains("-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m");
    }

    @Test
    void operationsManualDocumentsManualDeployAndRollback() throws IOException {
        String manual = readRequired("deploy/production/OPERATIONS.md");

        assertThat(manual).contains("mvn clean package -DskipTests");
        assertThat(manual).contains("systemctl restart osheeep-server");
        assertThat(manual).contains("backup/osheeep-server-$(date +%Y%m%d-%H%M%S).jar");
        assertThat(manual).contains("curl --fail --silent http://127.0.0.1:8080/actuator/health");
        assertThat(manual).doesNotContain("OSHEEEP_WECHAT_APP_SECRET=");
        assertThat(manual).doesNotContain("OSHEEEP_DB_PASSWORD=");
    }

    private String readRequired(String relativePath) throws IOException {
        Path path = root.resolve(relativePath);
        assertThat(path).as("required deployment asset %s", relativePath).exists();
        return Files.readString(path);
    }
}
