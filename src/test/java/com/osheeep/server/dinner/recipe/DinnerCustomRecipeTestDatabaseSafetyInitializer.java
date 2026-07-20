package com.osheeep.server.dinner.recipe;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

public final class DinnerCustomRecipeTestDatabaseSafetyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String FAILURE_MESSAGE =
            "Dinner custom recipe integration test requires an explicit dedicated test database";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        requireDedicatedDatabase(applicationContext.getEnvironment());
    }

    private void requireDedicatedDatabase(Environment environment) {
        String selectedDatabase = environment.getProperty("OSHEEEP_DB_NAME");
        String testDatabase = environment.getProperty("OSHEEEP_DB_TEST_NAME");
        String datasourceUrl = environment.getProperty("spring.datasource.url");
        String flywayUrl = environment.getProperty("spring.flyway.url");
        boolean localProfile = Arrays.asList(environment.getActiveProfiles()).contains("local");
        String datasourceCatalog = mysqlCatalog(datasourceUrl);

        if (!localProfile
                || !StringUtils.hasText(selectedDatabase)
                || !StringUtils.hasText(testDatabase)
                || !selectedDatabase.equals(testDatabase)
                || !testDatabase.equals(datasourceCatalog)
                || StringUtils.hasText(flywayUrl)) {
            throw new IllegalStateException(FAILURE_MESSAGE);
        }
    }

    private String mysqlCatalog(String jdbcUrl) {
        if (!StringUtils.hasText(jdbcUrl) || !jdbcUrl.startsWith("jdbc:mysql://")) {
            return null;
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            if (!"mysql".equalsIgnoreCase(uri.getScheme())
                    || !StringUtils.hasText(uri.getRawAuthority())) {
                return null;
            }
            String path = uri.getPath();
            if (path == null || !path.startsWith("/") || path.length() == 1) {
                return null;
            }
            String catalog = path.substring(1);
            return StringUtils.hasText(catalog) && !catalog.contains("/") ? catalog : null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }
}
