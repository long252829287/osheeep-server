package com.osheeep.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

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
    void productionApplicationDisablesTheUnusedGeneratedDefaultUser() {
        SpringBootApplication application =
                OsheeepServerApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(Arrays.asList(application.exclude()))
                .contains(UserDetailsServiceAutoConfiguration.class);
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
        assertThat(manual).contains("mysqldump");
        assertThat(manual).contains("osheeep_restore_verify_");
        assertThat(manual).contains("TEMP_DATABASES_REMAINING=0");
        assertThat(manual).contains("curl --fail --silent http://127.0.0.1:8080/actuator/health");
        assertThat(manual).contains("00-osheeep-rate-limit.conf");
        assertThat(manual).contains("osheeep-api-locations.conf");
        assertThat(manual).contains("https://www.osheeep.com/healthz");
        assertThat(manual).contains("小家开饭-生产健康检查");
        assertThat(manual).contains("小家开饭-健康检查失败");
        assertThat(manual).contains("小家开饭-CVM基础故障");
        assertThat(manual).contains("小家开饭-生产告警邮件");
        assertThat(manual).doesNotContain("OSHEEEP_WECHAT_APP_SECRET=");
        assertThat(manual).doesNotContain("OSHEEEP_DB_PASSWORD=");
    }

    @Test
    void nginxAssetsDefineApprovedRateLimitsAndHealthEndpoint() throws IOException {
        String zones = readRequired("deploy/production/nginx/00-osheeep-rate-limit.conf");
        String locations = readRequired("deploy/production/nginx/osheeep-api-locations.conf");

        assertThat(zones).contains("map $uri $osheeep_auth_limit_key");
        assertThat(zones).contains("/api/auth/wechat $binary_remote_addr;");
        assertThat(zones).contains("zone=osheeep_auth:10m rate=12r/m");
        assertThat(zones).contains("zone=osheeep_api:10m rate=300r/m");
        assertThat(zones).contains("limit_conn_zone $binary_remote_addr zone=osheeep_conn:10m;");
        assertThat(locations).contains("location = /healthz");
        assertThat(locations).contains("proxy_pass http://127.0.0.1:8080/actuator/health;");
        assertThat(locations).contains("limit_req zone=osheeep_auth burst=5 nodelay;");
        assertThat(locations).contains("limit_req zone=osheeep_api burst=60 nodelay;");
        assertThat(locations).contains("limit_conn osheeep_conn 20;");
        assertThat(locations).contains("limit_req_status 429;");
        assertThat(locations).contains("limit_conn_status 429;");
        assertThat(locations).doesNotContain("3000");
    }

    private String readRequired(String relativePath) throws IOException {
        Path path = root.resolve(relativePath);
        assertThat(path).as("required deployment asset %s", relativePath).exists();
        return Files.readString(path);
    }
}
