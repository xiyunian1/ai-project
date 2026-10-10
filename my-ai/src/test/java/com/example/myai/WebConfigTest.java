package com.example.myai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class WebConfigTest {

    @Test
    void filtersNullOriginByDefault() throws Exception {
        WebConfig config = new WebConfig(null, "http://localhost:5173,null,http://127.0.0.1:5173");

        assertThat(allowedOrigins(config))
                .containsExactly("http://localhost:5173", "http://127.0.0.1:5173");
    }

    @Test
    void allowsNullOriginOnlyWhenExplicitlyEnabled() throws Exception {
        WebConfig config = new WebConfig(null, "http://localhost:5173,null", true);

        assertThat(allowedOrigins(config))
                .containsExactly("http://localhost:5173", "null");
    }

    private static String[] allowedOrigins(WebConfig config) throws Exception {
        Field field = WebConfig.class.getDeclaredField("allowedOrigins");
        field.setAccessible(true);
        return (String[]) field.get(config);
    }
}
