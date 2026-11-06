package com.achpayments.param;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.achpayments.common.AppLogRepository;
import com.achpayments.common.SecurityConfig;

/** UC-SYS-PARAM-01 preconditions and EX-02, with the database mocked out. */
@WebMvcTest(ParamController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.admin-password=admin-pw", "app.operator-password=operator-pw",
        "app.member-password=member-pw"})
class ParamSecurityTest {

    private static final String BODY = """
            {"paramDesc":"d","paramRawType":"STRING","paramRawValue":"18:00:00","lastModBy":"hacker"}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ParamRepository repo;

    @MockitoBean
    ParamService service;

    @MockitoBean
    AppLogRepository appLog;

    @Test
    void anonymousUserMustLogIn() throws Exception {
        mvc.perform(get("/api/params/systems")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminGets403AndASecurityWarningInAppLog() throws Exception {
        mvc.perform(get("/api/params/systems").with(httpBasic("ACHOperator", "operator-pw")))
                .andExpect(status().isForbidden());

        verify(appLog).log(eq("WARN"), startsWith("Access denied: user 'ACHOperator' attempted GET /api/params/systems"),
                isNull());
    }

    @Test
    void nonAdminCannotSaveThroughTheApi() throws Exception {
        mvc.perform(put("/api/params/ACH_ORIG/FED_FILE_CUTOFF_TIME").with(httpBasic("ACHOperator", "operator-pw"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(service, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void adminGetsBasAppList() throws Exception {
        mvc.perform(get("/api/params/systems").with(httpBasic("admin", "admin-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].systemName").value("ACH_ORIG"))
                .andExpect(jsonPath("$[0].appName").value("ACH Origination UI"));
    }

    @Test
    void lastModByComesFromTheLoginNotTheRequestBody() throws Exception {
        mvc.perform(put("/api/params/ACH_ORIG/FED_FILE_CUTOFF_TIME").with(httpBasic("admin", "admin-pw"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        verify(service).save("ACH_ORIG", "FED_FILE_CUTOFF_TIME", "d", "STRING", "18:00:00", "admin");
    }

    @Test
    void saveWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(put("/api/params/ACH_ORIG/FED_FILE_CUTOFF_TIME").with(httpBasic("admin", "admin-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        verify(service, never()).save(any(), any(), any(), any(), any(), any());
        verify(appLog).log(eq("WARN"),
                eq("CSRF check failed: missing or invalid token on PUT /api/params/ACH_ORIG/FED_FILE_CUTOFF_TIME"), isNull());
    }

    @Test
    void validationErrorsComeBackAs400WithTheMessage() throws Exception {
        when(service.save(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ParamService.InvalidParamException("Value must be a valid integer."));

        mvc.perform(put("/api/params/ACH_ORIG/MAX_RETRY").with(httpBasic("admin", "admin-pw"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Value must be a valid integer."));
    }
}
