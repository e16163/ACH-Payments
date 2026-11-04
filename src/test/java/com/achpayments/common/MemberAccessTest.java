package com.achpayments.common;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.achpayments.param.ParamController;
import com.achpayments.param.ParamRepository;
import com.achpayments.param.ParamService;
import com.achpayments.transfer.TransferController;
import com.achpayments.transfer.TransferDates;

import tools.jackson.databind.json.JsonMapper;

/** USE-CASES T7: members must pass MFA before the transfer APIs; admins and members stay in their own areas. */
@WebMvcTest({MeController.class, TransferController.class, ParamController.class})
@Import({SecurityConfig.class, MfaService.class, MemberAccessTest.Clocks.class})
@TestPropertySource(properties = {"app.admin-password=admin-pw", "app.operator-password=operator-pw",
        "app.member-password=member-pw"})
class MemberAccessTest {

    @TestConfiguration
    static class Clocks {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private static final String MEMBER = "member"; // the login name
    private static final String MEMBER_NO = "M10982347"; // Ba's sample member, from app.member-number

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppLogRepository appLog;

    @MockitoBean
    TransferDates dates;

    @MockitoBean
    com.achpayments.transfer.TransferService transfers;

    @MockitoBean
    BankingCalendar calendar;

    @MockitoBean
    ParamRepository paramRepo;

    @MockitoBean
    ParamService paramService;

    private String sendCode(MockHttpSession session) throws Exception {
        String body = mvc.perform(post("/api/member/mfa/send").session(session).with(httpBasic(MEMBER, "member-pw")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body).get("demoCode").asString();
    }

    private org.springframework.test.web.servlet.ResultActions verify(MockHttpSession session, String code) throws Exception {
        return mvc.perform(post("/api/member/mfa/verify").session(session).with(httpBasic(MEMBER, "member-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    @Test
    void memberIsBlockedFromTransfersUntilMfaIsVerified() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/api/transfers/dates").session(session).with(httpBasic(MEMBER, "member-pw")))
                .andExpect(status().isForbidden());

        String code = sendCode(session);
        verify(session, code.equals("000000") ? "111111" : "000000").andExpect(status().isBadRequest());
        verify(session, code).andExpect(status().isOk()).andExpect(jsonPath("$.mfaVerified").value(true));

        mvc.perform(get("/api/transfers/dates").session(session).with(httpBasic(MEMBER, "member-pw")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me").session(session).with(httpBasic(MEMBER, "member-pw")))
                .andExpect(jsonPath("$.username").value(MEMBER))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"))
                .andExpect(jsonPath("$.mfaVerified").value(true));
    }

    @Test
    void theMemberAndLastModByComeFromTheLoginNotTheRequestBody() throws Exception {
        MockHttpSession session = new MockHttpSession();
        verify(session, sendCode(session)).andExpect(status().isOk());
        mvc.perform(post("/api/transfers").session(session).with(httpBasic(MEMBER, "member-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"origAcctNo\":\"100049281920\",\"amount\":10.00,\"memberNo\":\"SOMEONE-ELSE\",\"lastModBy\":\"hacker\"}"))
                .andExpect(status().isCreated());
        org.mockito.Mockito.verify(transfers).submit(org.mockito.ArgumentMatchers.eq(MEMBER_NO), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(transfers, org.mockito.Mockito.never())
                .submit(org.mockito.ArgumentMatchers.eq("SOMEONE-ELSE"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fiveWrongCodesUseUpTheChallenge() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String code = sendCode(session);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) verify(session, wrong).andExpect(status().isBadRequest());
        verify(session, code).andExpect(status().isBadRequest()); // the right code no longer works
    }

    @Test
    void aCodeIsRequiredBeforeVerifying() throws Exception {
        verify(new MockHttpSession(), "123456").andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotUseMemberApis() throws Exception {
        mvc.perform(post("/api/member/mfa/send").with(httpBasic("admin", "admin-pw")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/transfers/dates").with(httpBasic("admin", "admin-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/routing/021000021").with(httpBasic("admin", "admin-pw"))).andExpect(status().isForbidden());
    }

    @Test
    void memberCannotUseTheParameterUi() throws Exception {
        mvc.perform(get("/api/params/systems").with(httpBasic(MEMBER, "member-pw"))).andExpect(status().isForbidden());
    }

    @Test
    void anonymousMustLogIn() throws Exception {
        mvc.perform(get("/api/transfers/dates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                // no WWW-Authenticate, so the browser shows the app's sign-in page, not its own popup
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.error").value("Please sign in."));
    }

    @Test
    void signInPageLogsInAndSignOutLogsOut() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/login").with(csrf())
                .param("username", MEMBER).param("password", "member-pw"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession();
        mvc.perform(get("/api/me").session(session)).andExpect(jsonPath("$.username").value(MEMBER));

        mvc.perform(post("/api/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/login").with(csrf()).param("username", MEMBER).param("password", "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Incorrect username or password."));
    }

    @Test
    void signInNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/api/login").param("username", MEMBER).param("password", "member-pw"))
                .andExpect(status().isForbidden());
    }
}
