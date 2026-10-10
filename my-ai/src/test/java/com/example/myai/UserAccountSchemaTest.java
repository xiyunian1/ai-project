package com.example.myai;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountSchemaTest {
    @Test
    void roleAndEnabledColumnsHaveSqlServerSafeDefaultsForExistingRows() throws Exception {
        Field role = UserAccount.class.getDeclaredField("role");
        Field enabled = UserAccount.class.getDeclaredField("enabled");

        assertThat(role.getAnnotation(Column.class).columnDefinition())
                .contains("default 'USER'");
        assertThat(enabled.getAnnotation(Column.class).columnDefinition())
                .contains("default 1");
    }
}
