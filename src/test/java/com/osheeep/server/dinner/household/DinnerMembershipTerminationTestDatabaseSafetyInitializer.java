package com.osheeep.server.dinner.household;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

final class DinnerMembershipTerminationTestDatabaseSafetyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String FAILURE_MESSAGE =
            "Membership termination integration test requires an explicitly opted-in "
                    + "disposable loopback MySQL database";

    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "0:0:0:0:0:0:0:1");

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment environment = applicationContext.getEnvironment();
        String selectedDatabase = environment.getProperty("OSHEEEP_DB_NAME");
        String testDatabase = environment.getProperty("OSHEEEP_DB_TEST_NAME");
        String rawSelectedDatabase = System.getenv("OSHEEEP_DB_NAME");
        String rawTestDatabase = System.getenv("OSHEEEP_DB_TEST_NAME");
        String datasourceUrl = environment.getProperty("spring.datasource.url");
        String flywayUrl = environment.getProperty("spring.flyway.url");
        MysqlTarget target = mysqlTarget(datasourceUrl);

        if (!Arrays.asList(environment.getActiveProfiles()).contains("local")
                || !"true".equals(System.getenv("OSHEEEP_ALLOW_EPHEMERAL_DATABASES"))
                || !StringUtils.hasText(selectedDatabase)
                || !selectedDatabase.equals(testDatabase)
                || !selectedDatabase.equals(rawSelectedDatabase)
                || !selectedDatabase.equals(rawTestDatabase)
                || target == null
                || !selectedDatabase.equals(target.catalog())
                || !LOOPBACK_HOSTS.contains(target.host().toLowerCase())
                || StringUtils.hasText(flywayUrl)) {
            throw new IllegalStateException(FAILURE_MESSAGE);
        }
    }

    private MysqlTarget mysqlTarget(String jdbcUrl) {
        if (!StringUtils.hasText(jdbcUrl) || !jdbcUrl.startsWith("jdbc:mysql://")) {
            return null;
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            String host = uri.getHost();
            String path = uri.getPath();
            if (!"mysql".equalsIgnoreCase(uri.getScheme())
                    || !StringUtils.hasText(host)
                    || path == null
                    || !path.startsWith("/")
                    || path.length() == 1) {
                return null;
            }
            String catalog = path.substring(1);
            if (!StringUtils.hasText(catalog) || catalog.contains("/")) {
                return null;
            }
            return new MysqlTarget(host, catalog);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private record MysqlTarget(String host, String catalog) {
    }
}
