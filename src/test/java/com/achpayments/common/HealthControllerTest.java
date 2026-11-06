package com.achpayments.common;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class) // proves /api/health stays public under the real security rules
@TestPropertySource(properties = {"app.admin-password=x", "app.orig-password=y", "app.recv-password=y", "app.member-password=z"})
class HealthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JdbcTemplate jdbc;

    @MockitoBean
    AppLogRepository appLog;

    @Test
    void reportsOkWhenDatabaseAnswers() throws Exception {
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);

        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.db").value("OK"));
    }

    @Test
    void reportsDownWhenDatabaseFails() throws Exception {
        when(jdbc.queryForObject(anyString(), any(Class.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.db").value("DOWN"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
