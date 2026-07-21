package com.osheeep.server.dinner.menu;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sql.DataSource;

final class DinnerEphemeralCatalogHarness implements AutoCloseable {

    static final String FAILURE_MESSAGE =
            "Dinner migration integration test requires guarded ephemeral catalogs";

    private static final int MYSQL_IDENTIFIER_LIMIT = 64;
    private static final int MAX_BASE_CATALOG_LENGTH = 15;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern RUN_ID = Pattern.compile("[0-9a-f]{32}");

    private final DataSource baseDataSource;
    private final String baseCatalog;
    private final String runId;
    private final String freshCatalog;
    private final String v4Catalog;
    private final String v6Catalog;
    private final Set<String> exactRunCatalogs;
    private final Set<String> createdCatalogs = new LinkedHashSet<>();

    static DinnerEphemeralCatalogHarness fromEnvironment(
            DataSource baseDataSource,
            String effectiveJdbcUrl
    ) {
        return new DinnerEphemeralCatalogHarness(
                baseDataSource,
                effectiveJdbcUrl,
                System.getenv("OSHEEEP_DB_NAME"),
                System.getenv("OSHEEEP_DB_TEST_NAME"),
                System.getenv("OSHEEEP_ALLOW_EPHEMERAL_DATABASES"),
                UUID.randomUUID().toString().replace("-", ""));
    }

    DinnerEphemeralCatalogHarness(
            DataSource baseDataSource,
            String effectiveJdbcUrl,
            String environmentSelectedCatalog,
            String environmentTestCatalog,
            String environmentOptIn,
            String runId
    ) {
        requireConfiguration(
                baseDataSource,
                effectiveJdbcUrl,
                environmentSelectedCatalog,
                environmentTestCatalog,
                environmentOptIn,
                runId);
        this.baseDataSource = baseDataSource;
        this.baseCatalog = environmentTestCatalog;
        this.runId = runId;
        this.freshCatalog = generatedName("fresh");
        this.v4Catalog = generatedName("v4");
        this.v6Catalog = generatedName("v6");
        this.exactRunCatalogs = Set.of(freshCatalog, v4Catalog, v6Catalog);
    }

    String freshCatalog() {
        return freshCatalog;
    }

    String v4Catalog() {
        return v4Catalog;
    }

    String v6Catalog() {
        return v6Catalog;
    }

    synchronized Set<String> createdCatalogs() {
        return Set.copyOf(createdCatalogs);
    }

    synchronized void createCatalog(String catalog) {
        requireCurrentRunCatalog(catalog);
        if (createdCatalogs.contains(catalog)) {
            throw unsafe();
        }
        String quotedCatalog = quoteIdentifier(catalog);
        try (Connection connection = baseDataSource.getConnection()) {
            requireGuardedBaseConnection(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE DATABASE " + quotedCatalog
                                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                createdCatalogs.add(catalog);
            }
        } catch (SQLException | RuntimeException exception) {
            throw unsafe(exception);
        }
    }

    synchronized DataSource dataSourceFor(String catalog) {
        requireTrackedCurrentRunCatalog(catalog);
        return new CatalogSwitchingDataSource(baseDataSource, baseCatalog, catalog);
    }

    void requireActiveCatalog(DataSource dataSource, String catalog) {
        synchronized (this) {
            requireTrackedCurrentRunCatalog(catalog);
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!Objects.equals(catalog, queryScalar(connection, "SELECT DATABASE()"))) {
                throw unsafe();
            }
        } catch (SQLException exception) {
            throw unsafe(exception);
        }
    }

    synchronized void dropCatalog(String catalog) {
        requireTrackedCurrentRunCatalog(catalog);
        String quotedCatalog = quoteIdentifier(catalog);
        try (Connection connection = baseDataSource.getConnection()) {
            requireGuardedBaseConnection(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE " + quotedCatalog);
                createdCatalogs.remove(catalog);
            }
        } catch (SQLException | RuntimeException exception) {
            throw unsafe(exception);
        }
    }

    synchronized void dropAll() {
        RuntimeException failure = null;
        for (String catalog : new ArrayList<>(createdCatalogs)) {
            try {
                dropCatalog(catalog);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        dropAll();
    }

    private void requireConfiguration(
            DataSource dataSource,
            String jdbcUrl,
            String selectedCatalog,
            String testCatalog,
            String optIn,
            String candidateRunId
    ) {
        if (dataSource == null
                || !hasText(selectedCatalog)
                || !Objects.equals(selectedCatalog, testCatalog)
                || !"true".equals(optIn)
                || !safeIdentifier(testCatalog)
                || testCatalog.length() > MAX_BASE_CATALOG_LENGTH
                || !hasText(candidateRunId)
                || !RUN_ID.matcher(candidateRunId).matches()) {
            throw unsafe();
        }
        JdbcLocation location = parseJdbcLocation(jdbcUrl);
        if (location == null
                || !allowedLoopbackHost(location.host())
                || !Objects.equals(testCatalog, location.catalog())) {
            throw unsafe();
        }
        for (String suffix : List.of("fresh", "v4", "v6")) {
            String generated = testCatalog + "_ephemeral_" + candidateRunId + "_" + suffix;
            if (!safeIdentifier(generated)
                    || generated.length() > MYSQL_IDENTIFIER_LIMIT) {
                throw unsafe();
            }
        }
    }

    private JdbcLocation parseJdbcLocation(String jdbcUrl) {
        if (!hasText(jdbcUrl) || !jdbcUrl.startsWith("jdbc:mysql://")) {
            return null;
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            if (!"mysql".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            String path = uri.getPath();
            if (!hasText(path)
                    || !path.startsWith("/")
                    || path.length() == 1
                    || path.substring(1).contains("/")) {
                return null;
            }
            return new JdbcLocation(normalizeHost(uri.getHost()), path.substring(1));
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private boolean allowedLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
            return true;
        }
        if (!host.contains(":")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address instanceof Inet6Address && address.isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private String normalizeHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private void requireGuardedBaseConnection(Connection connection) throws SQLException {
        if (!Objects.equals(baseCatalog, queryScalar(connection, "SELECT DATABASE()"))) {
            throw unsafe();
        }
        String version = queryScalar(connection, "SELECT VERSION()");
        if (version == null || !version.startsWith("8.")) {
            throw unsafe();
        }
    }

    private String queryScalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private String generatedName(String suffix) {
        return baseCatalog + "_ephemeral_" + runId + "_" + suffix;
    }

    private synchronized void requireTrackedCurrentRunCatalog(String catalog) {
        requireCurrentRunCatalog(catalog);
        if (!createdCatalogs.contains(catalog)) {
            throw unsafe();
        }
    }

    private void requireCurrentRunCatalog(String catalog) {
        if (!safeIdentifier(catalog)
                || catalog.length() > MYSQL_IDENTIFIER_LIMIT
                || !exactRunCatalogs.contains(catalog)
                || !catalog.matches(
                        Pattern.quote(baseCatalog)
                                + "_ephemeral_"
                                + runId
                                + "_(fresh|v4|v6)")) {
            throw unsafe();
        }
    }

    private String quoteIdentifier(String identifier) {
        if (!safeIdentifier(identifier)
                || identifier.length() > MYSQL_IDENTIFIER_LIMIT) {
            throw unsafe();
        }
        return "`" + identifier + "`";
    }

    private boolean safeIdentifier(String value) {
        return hasText(value) && IDENTIFIER.matcher(value).matches();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private IllegalStateException unsafe() {
        return new IllegalStateException(FAILURE_MESSAGE);
    }

    private IllegalStateException unsafe(Exception cause) {
        return new IllegalStateException(FAILURE_MESSAGE, cause);
    }

    private record JdbcLocation(String host, String catalog) {
    }

    private static final class CatalogSwitchingDataSource implements DataSource {

        private final DataSource delegate;
        private final String baseCatalog;
        private final String targetCatalog;

        private CatalogSwitchingDataSource(
                DataSource delegate,
                String baseCatalog,
                String targetCatalog
        ) {
            this.delegate = delegate;
            this.baseCatalog = baseCatalog;
            this.targetCatalog = targetCatalog;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return switchCatalog(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return switchCatalog(delegate.getConnection(username, password));
        }

        private Connection switchCatalog(Connection connection) throws SQLException {
            try {
                connection.setCatalog(targetCatalog);
                return restoringConnection(connection, baseCatalog);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.close();
                } catch (SQLException | RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    private static Connection restoringConnection(
            Connection delegate,
            String baseCatalog
    ) {
        return (Connection) Proxy.newProxyInstance(
                DinnerEphemeralCatalogHarness.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new RestoringConnectionHandler(delegate, baseCatalog));
    }

    private static final class RestoringConnectionHandler
            implements java.lang.reflect.InvocationHandler {

        private final Connection delegate;
        private final String baseCatalog;
        private boolean closed;

        private RestoringConnectionHandler(Connection delegate, String baseCatalog) {
            this.delegate = delegate;
            this.baseCatalog = baseCatalog;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                close();
                return null;
            }
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        private void close() throws Throwable {
            if (closed) {
                return;
            }
            closed = true;
            Throwable failure = null;
            try {
                delegate.setCatalog(baseCatalog);
            } catch (SQLException | RuntimeException exception) {
                failure = exception;
            } finally {
                try {
                    delegate.close();
                } catch (SQLException | RuntimeException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
