package com.example.myai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SqlAnalysisServiceCustomDbTest {

    private final SqlAnalysisService service = new SqlAnalysisService(
            mock(JdbcTemplate.class),
            WebClient.builder(),
            new ObjectMapper()
    );

    @Test
    void customDatabaseConnectionsAreDisabledByDefault() throws Exception {
        SqlAnalysisService.DbConnectionConfig config = new SqlAnalysisService.DbConnectionConfig(
                true,
                "127.0.0.1",
                1433,
                "warehouse",
                "readonly_user",
                "password",
                "jdbc:invalid://127.0.0.1/warehouse",
                "invalid.Driver",
                "dbo.inout",
                false
        );

        assertCustomDbRejected(config);
    }

    @Test
    void suppliedDbConfigIsRejectedByDefaultEvenWhenDisabledInPayload() throws Exception {
        SqlAnalysisService.DbConnectionConfig config = new SqlAnalysisService.DbConnectionConfig(
                false,
                "",
                null,
                "",
                "",
                "",
                "",
                "",
                "",
                false
        );

        assertCustomDbRejected(config);
    }

    private void assertCustomDbRejected(SqlAnalysisService.DbConnectionConfig config) throws Exception {
        Method method = SqlAnalysisService.class.getDeclaredMethod(
                "resolveExecutionContext",
                SqlAnalysisService.DbConnectionConfig.class
        );
        method.setAccessible(true);

        try {
            method.invoke(service, config);
        } catch (InvocationTargetException ex) {
            assertThat(ex.getCause()).isInstanceOf(ApiException.class);
            ApiException apiException = (ApiException) ex.getCause();
            assertThat(apiException.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(apiException.getMessage()).isEqualTo("Custom database connections are disabled.");
            return;
        }

        throw new AssertionError("Expected custom database config to be rejected before opening a connection");
    }
}
