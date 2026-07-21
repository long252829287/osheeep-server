package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DinnerEphemeralCatalogHarnessTest {

    private static final String BASE = "dinner_it";
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/dinner_it";
    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void missingExplicitOptInFailsBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);

        assertUnsafe(() -> harness(dataSource, URL, BASE, BASE, null, RUN_ID));

        verifyNoInteractions(dataSource);
    }

    @Test
    void rawSelectedAndTestCatalogsMustMatchBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);

        assertUnsafe(() -> harness(
                dataSource, URL, "production", BASE, "true", RUN_ID));

        verifyNoInteractions(dataSource);
    }

    @Test
    void jdbcUrlCatalogMustMatchTheRawTestCatalogBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);

        assertUnsafe(() -> harness(
                dataSource,
                "jdbc:mysql://127.0.0.1:3306/another_test_catalog",
                BASE,
                BASE,
                "true",
                RUN_ID));

        verifyNoInteractions(dataSource);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://db.internal:3306/dinner_it",
            "jdbc:mysql://192.168.1.20:3306/dinner_it",
            "jdbc:postgresql://127.0.0.1:5432/dinner_it"
    })
    void remoteOrNonMysqlJdbcHostFailsBeforeOpeningAConnection(String jdbcUrl) {
        DataSource dataSource = mock(DataSource.class);

        assertUnsafe(() -> harness(dataSource, jdbcUrl, BASE, BASE, "true", RUN_ID));

        verifyNoInteractions(dataSource);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://localhost:3306/dinner_it",
            "jdbc:mysql://[::1]:3306/dinner_it",
            "jdbc:mysql://[0:0:0:0:0:0:0:1]:3306/dinner_it"
    })
    void acceptsOnlyExplicitLoopbackMysqlHosts(String jdbcUrl) {
        DataSource dataSource = mock(DataSource.class);

        assertThatCode(() -> harness(
                dataSource, jdbcUrl, BASE, BASE, "true", RUN_ID))
                .doesNotThrowAnyException();

        verifyNoInteractions(dataSource);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "unsafe-name",
            "unsafe name",
            "abcdefghijklmnop"
    })
    void unsafeBaseOrGeneratedNameLengthFailsBeforeOpeningAConnection(String baseCatalog) {
        DataSource dataSource = mock(DataSource.class);
        String jdbcUrl = "jdbc:mysql://127.0.0.1:3306/" + baseCatalog;

        assertUnsafe(() -> harness(
                dataSource, jdbcUrl, baseCatalog, baseCatalog, "true", RUN_ID));

        verifyNoInteractions(dataSource);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdeg",
            "0123456789ABCDEF0123456789ABCDEF"
    })
    void generatedRunIdMustBeExactlyLowercaseThirtyTwoHex(String runId) {
        DataSource dataSource = mock(DataSource.class);

        assertUnsafe(() -> harness(dataSource, URL, BASE, BASE, "true", runId));

        verifyNoInteractions(dataSource);
    }

    @Test
    void generatedNamesAreExactAndWithinMysqlIdentifierLimit() {
        DinnerEphemeralCatalogHarness harness = harness(
                mock(DataSource.class), URL, BASE, BASE, "true", RUN_ID);

        assertThat(harness.freshCatalog())
                .isEqualTo(BASE + "_ephemeral_" + RUN_ID + "_fresh")
                .hasSizeLessThanOrEqualTo(64);
        assertThat(harness.v4Catalog())
                .isEqualTo(BASE + "_ephemeral_" + RUN_ID + "_v4")
                .hasSizeLessThanOrEqualTo(64);
        assertThat(harness.v6Catalog())
                .isEqualTo(BASE + "_ephemeral_" + RUN_ID + "_v6")
                .hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void untrackedDropAndCatalogSwitchFailBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);

        assertUnsafe(() -> harness.dropCatalog(harness.freshCatalog()));
        assertUnsafe(() -> harness.dataSourceFor(harness.freshCatalog()));

        verifyNoInteractions(dataSource);
    }

    @Test
    void generatedNameMismatchFailsBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);

        assertUnsafe(() -> harness.createCatalog(
                BASE + "_ephemeral_" + RUN_ID + "_fresh_copy"));

        verifyNoInteractions(dataSource);
    }

    @Test
    void redirectedActualBaseCatalogFailsBeforeDdlAndIsNeverTracked() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet catalogResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE()"))
                .thenReturn(catalogResult);
        when(catalogResult.next()).thenReturn(true);
        when(catalogResult.getString(1)).thenReturn("redirected_catalog");
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);

        assertUnsafe(() -> harness.createCatalog(harness.freshCatalog()));

        verify(statement, never()).executeUpdate(anyString());
        assertThat(harness.createdCatalogs()).isEmpty();
    }

    @Test
    void nonMysqlEightBaseFailsBeforeDdlAndIsNeverTracked() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        GuardedConnection base = guardedConnection();
        when(dataSource.getConnection()).thenReturn(base.connection());
        when(base.versionResult().getString(1)).thenReturn("5.7.44");
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);

        assertUnsafe(() -> harness.createCatalog(harness.freshCatalog()));

        verify(base.statement(), never()).executeUpdate(anyString());
        assertThat(harness.createdCatalogs()).isEmpty();
    }

    @Test
    void successfulCreateRemainsTrackedWhenStatementCloseFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        GuardedConnection create = guardedConnection();
        when(dataSource.getConnection()).thenReturn(create.connection());
        doThrow(new SQLException("statement close failed")).when(create.statement()).close();
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);

        assertUnsafe(() -> harness.createCatalog(harness.freshCatalog()));

        assertThat(harness.createdCatalogs()).containsExactly(harness.freshCatalog());
        verify(create.statement()).executeUpdate(
                "CREATE DATABASE `" + harness.freshCatalog()
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }

    @Test
    void failedCreateIsNeverTracked() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        GuardedConnection create = guardedConnection();
        when(dataSource.getConnection()).thenReturn(create.connection());
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);
        doThrow(new SQLException("create failed"))
                .when(create.statement())
                .executeUpdate(
                        "CREATE DATABASE `" + harness.freshCatalog()
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");

        assertUnsafe(() -> harness.createCatalog(harness.freshCatalog()));

        assertThat(harness.createdCatalogs()).isEmpty();
    }

    @Test
    void successfulDropIsUntrackedWhenStatementCloseFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        GuardedConnection create = guardedConnection();
        GuardedConnection drop = guardedConnection();
        when(dataSource.getConnection())
                .thenReturn(create.connection())
                .thenReturn(drop.connection());
        doThrow(new SQLException("statement close failed")).when(drop.statement()).close();
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);
        harness.createCatalog(harness.freshCatalog());

        assertUnsafe(() -> harness.dropCatalog(harness.freshCatalog()));

        assertThat(harness.createdCatalogs()).isEmpty();
        verify(drop.statement()).executeUpdate(
                "DROP DATABASE `" + harness.freshCatalog() + "`");
    }

    @Test
    void dropAllAttemptsEveryTrackedCatalogAfterTheFirstDropFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        GuardedConnection createFresh = guardedConnection();
        GuardedConnection createV4 = guardedConnection();
        GuardedConnection createV6 = guardedConnection();
        GuardedConnection dropFresh = guardedConnection();
        GuardedConnection dropV4 = guardedConnection();
        GuardedConnection dropV6 = guardedConnection();
        when(dataSource.getConnection()).thenReturn(
                createFresh.connection(),
                createV4.connection(),
                createV6.connection(),
                dropFresh.connection(),
                dropV4.connection(),
                dropV6.connection());
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);
        harness.createCatalog(harness.freshCatalog());
        harness.createCatalog(harness.v4Catalog());
        harness.createCatalog(harness.v6Catalog());
        doThrow(new SQLException("drop failed"))
                .when(dropFresh.statement())
                .executeUpdate("DROP DATABASE `" + harness.freshCatalog() + "`");

        assertUnsafe(harness::dropAll);

        verify(dropFresh.statement()).executeUpdate(
                "DROP DATABASE `" + harness.freshCatalog() + "`");
        verify(dropV4.statement()).executeUpdate(
                "DROP DATABASE `" + harness.v4Catalog() + "`");
        verify(dropV6.statement()).executeUpdate(
                "DROP DATABASE `" + harness.v6Catalog() + "`");
        assertThat(harness.createdCatalogs()).containsExactly(harness.freshCatalog());
    }

    @Test
    void bothConnectionOverloadsSwitchAndRestoreTheExactCatalog() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection createConnection = baseConnection(dataSource);
        Connection plainConnection = mock(Connection.class);
        Connection credentialConnection = mock(Connection.class);
        when(dataSource.getConnection())
                .thenReturn(createConnection)
                .thenReturn(plainConnection);
        when(dataSource.getConnection("user", "secret"))
                .thenReturn(credentialConnection);
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);
        harness.createCatalog(harness.freshCatalog());

        DataSource switched = harness.dataSourceFor(harness.freshCatalog());
        try (Connection ignored = switched.getConnection()) {
            verify(plainConnection).setCatalog(harness.freshCatalog());
        }
        try (Connection ignored = switched.getConnection("user", "secret")) {
            verify(credentialConnection).setCatalog(harness.freshCatalog());
        }

        verify(plainConnection).setCatalog(BASE);
        verify(plainConnection).close();
        verify(credentialConnection).setCatalog(BASE);
        verify(credentialConnection).close();
    }

    @Test
    void failedCatalogSwitchClosesUnderlyingAndPreservesCloseFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection createConnection = baseConnection(dataSource);
        Connection underlying = mock(Connection.class);
        when(dataSource.getConnection())
                .thenReturn(createConnection)
                .thenReturn(underlying);
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);
        harness.createCatalog(harness.freshCatalog());
        SQLException switchFailure = new SQLException("switch failed");
        SQLException closeFailure = new SQLException("close failed");
        doThrow(switchFailure).when(underlying).setCatalog(harness.freshCatalog());
        doThrow(closeFailure).when(underlying).close();

        assertThatThrownBy(() -> harness.dataSourceFor(
                        harness.freshCatalog()).getConnection())
                .isSameAs(switchFailure)
                .satisfies(error -> assertThat(error.getSuppressed())
                        .containsExactly(closeFailure));

        verify(underlying).close();
        verify(underlying, never()).setCatalog(BASE);
    }

    @Test
    void closeAlwaysAttemptsRestoreAndCloseAndMergesFailures() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection createConnection = baseConnection(dataSource);
        Connection underlying = mock(Connection.class);
        when(dataSource.getConnection())
                .thenReturn(createConnection)
                .thenReturn(underlying);
        DinnerEphemeralCatalogHarness harness = harness(
                dataSource, URL, BASE, BASE, "true", RUN_ID);
        harness.createCatalog(harness.freshCatalog());
        Connection switched = harness.dataSourceFor(
                harness.freshCatalog()).getConnection();
        SQLException restoreFailure = new SQLException("restore failed");
        SQLException closeFailure = new SQLException("close failed");
        doThrow(restoreFailure).when(underlying).setCatalog(BASE);
        doThrow(closeFailure).when(underlying).close();

        assertThatThrownBy(switched::close)
                .isSameAs(restoreFailure)
                .satisfies(error -> assertThat(error.getSuppressed())
                        .containsExactly(closeFailure));

        verify(underlying).close();
    }

    private DinnerEphemeralCatalogHarness harness(
            DataSource dataSource,
            String jdbcUrl,
            String selectedCatalog,
            String testCatalog,
            String optIn,
            String runId
    ) {
        return new DinnerEphemeralCatalogHarness(
                dataSource, jdbcUrl, selectedCatalog, testCatalog, optIn, runId);
    }

    private Connection baseConnection(DataSource dataSource) throws Exception {
        GuardedConnection base = guardedConnection();
        when(dataSource.getConnection()).thenReturn(base.connection());
        return base.connection();
    }

    private GuardedConnection guardedConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement catalogStatement = mock(Statement.class);
        Statement versionStatement = mock(Statement.class);
        Statement ddlStatement = mock(Statement.class);
        ResultSet catalogResult = mock(ResultSet.class);
        ResultSet versionResult = mock(ResultSet.class);
        when(connection.createStatement())
                .thenReturn(catalogStatement, versionStatement, ddlStatement);
        when(catalogStatement.executeQuery("SELECT DATABASE()"))
                .thenReturn(catalogResult);
        when(versionStatement.executeQuery("SELECT VERSION()"))
                .thenReturn(versionResult);
        when(catalogResult.next()).thenReturn(true);
        when(catalogResult.getString(1)).thenReturn(BASE);
        when(versionResult.next()).thenReturn(true);
        when(versionResult.getString(1)).thenReturn("8.4.0");
        return new GuardedConnection(connection, ddlStatement, versionResult);
    }

    private void assertUnsafe(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DinnerEphemeralCatalogHarness.FAILURE_MESSAGE);
    }

    private record GuardedConnection(
            Connection connection,
            Statement statement,
            ResultSet versionResult
    ) {
    }
}
