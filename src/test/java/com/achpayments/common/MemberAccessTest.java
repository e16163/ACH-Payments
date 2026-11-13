package com.achpayments.common;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.ResultActions;

import com.achpayments.inbound.FedInboundSimulator;
import com.achpayments.inbound.ReceivingController;
import com.achpayments.inbound.ReceivingEngine;
import com.achpayments.param.ParamController;
import com.achpayments.param.ParamRepository;
import com.achpayments.backoffice.BackOfficeController;
import com.achpayments.outbound.EodController;
import com.achpayments.outbound.EodFileJob;
import com.achpayments.outbound.EodFileStore;
import com.achpayments.param.ParamCache;
import com.achpayments.param.ParamService;
import com.achpayments.transfer.ExternalAccountController;
import com.achpayments.transfer.TransferController;
import com.achpayments.transfer.TransferDates;

import tools.jackson.databind.json.JsonMapper;

/** USE-CASES T7: members must pass MFA before the transfer APIs; admins and members stay in their own areas. */
@WebMvcTest({MeController.class, TransferController.class, ParamController.class, ReceivingController.class,
        BackOfficeController.class, EodController.class, AppLogController.class, ExternalAccountController.class,
        com.achpayments.inbound.ReceivingProcessingController.class, com.achpayments.origination.OriginationProcessingController.class,
        com.achpayments.rules.BusinessRuleController.class})
@Import({SecurityConfig.class, MfaService.class, MemberNumbers.class, UnhandledErrorLogger.class, MemberAccessTest.Clocks.class})
@TestPropertySource(properties = {"app.admin-password=admin-pw", "app.orig-password=orig-pw", "app.recv-password=recv-pw", "app.comp-password=comp-pw",
        "app.member-password=member-pw",
        "app.ldap.url=ldap://localhost:1/dc=achpayments,dc=local"}) // nothing listens there: the fallback logins work with LDAP down
class MemberAccessTest {

    @TestConfiguration
    static class Clocks {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private static final String MEMBER = "member"; // the login name
    private static final String MEMBER_NO = "M10982347"; // The sample member, from app.member-number

    @Autowired
    MockMvc mvc;

    @MockitoBean
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean
    ReceivingEngine receivingEngine;

    @MockitoBean
    com.achpayments.inbound.ReceivedEntryProcessor entryProcessor;

    @MockitoBean
    com.achpayments.inbound.ReceivingWindow receivingWindow;

    @MockitoBean
    com.achpayments.inbound.CorePostingFileJob corePosting;

    @MockitoBean
    com.achpayments.origination.OriginationWindow originationWindow;

    @MockitoBean
    com.achpayments.transfer.OfacScreening ofacScreening;

    @MockitoBean
    EodFileJob eodFileJob;

    @MockitoBean
    EodFileStore eodFileStore;

    @MockitoBean
    ParamCache paramCache;

    @MockitoBean
    FedInboundSimulator fedInbound;

    @MockitoBean
    AppLogRepository appLog;

    @MockitoBean
    TransferDates dates;

    @MockitoBean
    com.achpayments.transfer.TransferService transfers;

    @MockitoBean
    com.achpayments.transfer.AccountService accountService;

    @MockitoBean
    BankingCalendar calendar;

    @MockitoBean
    com.achpayments.rules.BusinessRules businessRules;

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
                .andExpect(jsonPath("$.mfaVerified").value(true))
                .andExpect(jsonPath("$.memberNo").value(MEMBER_NO));
    }

    @Test
    void externalAccountsNeedMfaAndTheMembersOwnHolderId() throws Exception {
        String own = "/api/v1/holders/" + MEMBER_NO + "/external-accounts";
        String body = "{\"bankName\":\"Bank\",\"routingNumber\":\"021000021\",\"accountNumber\":\"12345678\","
                + "\"accountType\":\"CHECKING\",\"accountHolderName\":\"Jane Smith\"}";
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get(own).session(session).with(httpBasic(MEMBER, "member-pw"))).andExpect(status().isForbidden()); // no MFA yet
        verify(session, sendCode(session)).andExpect(status().isOk());

        mvc.perform(get(own).session(session).with(httpBasic(MEMBER, "member-pw"))).andExpect(status().isOk());
        org.mockito.Mockito.verify(transfers).externalAccounts(MEMBER_NO);
        mvc.perform(post(own).session(session).with(httpBasic(MEMBER, "member-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        org.mockito.Mockito.verify(transfers).addExternalAccount(org.mockito.ArgumentMatchers.eq(MEMBER_NO), org.mockito.ArgumentMatchers.any());

        String other = "/api/v1/holders/SOMEONE-ELSE/external-accounts";
        mvc.perform(get(other).session(session).with(httpBasic(MEMBER, "member-pw"))).andExpect(status().isForbidden());
        mvc.perform(post(other).session(session).with(httpBasic(MEMBER, "member-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        org.mockito.Mockito.verify(transfers, org.mockito.Mockito.never()).externalAccounts("SOMEONE-ELSE");
        mvc.perform(get(own).with(httpBasic("admin", "admin-pw"))).andExpect(status().isForbidden());
        mvc.perform(get(own).with(httpBasic("achorig", "orig-pw"))).andExpect(status().isForbidden());
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
    void accountHistoryAndInternalTransfersUseTheLoginsMemberNumberAndNeedMfa() throws Exception {
        mvc.perform(get("/api/member/accounts/100049281920/transactions").with(httpBasic(MEMBER, "member-pw")))
                .andExpect(status().isForbidden());
        MockHttpSession session = new MockHttpSession();
        verify(session, sendCode(session)).andExpect(status().isOk());
        mvc.perform(get("/api/member/accounts/100049281920/transactions").session(session).with(httpBasic(MEMBER, "member-pw")))
                .andExpect(status().isNotFound()); // the mock finds nothing, as for another member's account
        org.mockito.Mockito.verify(accountService).transactions(MEMBER_NO, "100049281920", 1, 10);
        mvc.perform(post("/api/transfers/internal").session(session).with(httpBasic(MEMBER, "member-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fromAcctNo\":\"100049281920\",\"toAcctNo\":\"100049281946\",\"amount\":5}"))
                .andExpect(status().isCreated());
        org.mockito.Mockito.verify(accountService).internalTransfer(org.mockito.ArgumentMatchers.eq(MEMBER_NO), org.mockito.ArgumentMatchers.any());
        mvc.perform(post("/api/transfers/internal").with(httpBasic("admin", "admin-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
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
    void onlyAdminsAndAchrecvCanSeeReceivingFiles() throws Exception {
        mvc.perform(get("/api/v1/ach/receiving/files").with(httpBasic(MEMBER, "member-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ach/receiving/files").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ach/receiving/files")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ach/receiving/files/receive").with(httpBasic(MEMBER, "member-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sample\":\"PAYROLL\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ach/receiving/files/receive").with(httpBasic("achrecv", "recv-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sample\":\"PAYROLL\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions signIn(String username, String password) throws Exception {
        return mvc.perform(post("/api/login").param("username", username).param("password", password).with(csrf()));
    }

    @Test
    void everySignInIsLoggedWithItsSystem() throws Exception {
        signIn("achorig", "orig-pw").andExpect(status().isNoContent());
        org.mockito.Mockito.verify(appLog).log("ACH_ORIG", "INFO", "User 'achorig' signed in");
        signIn("achrecv", "recv-pw").andExpect(status().isNoContent());
        org.mockito.Mockito.verify(appLog).log("ACH_RCV", "INFO", "User 'achrecv' signed in");
        signIn("admin", "admin-pw").andExpect(status().isNoContent());
        org.mockito.Mockito.verify(appLog).log("ACH_SYS", "INFO", "User 'admin' signed in");
        signIn(MEMBER, "member-pw").andExpect(status().isNoContent());
        org.mockito.Mockito.verify(appLog).log("ACH_ORIG", "INFO", "User 'member' signed in");
    }

    @Test
    void aFailedSignInIsLoggedWithoutThePassword() throws Exception {
        signIn("achrecv", "wrong-secret").andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(appLog).log("ACH_SYS", "WARN", "Failed sign-in for user 'achrecv'");
        org.mockito.Mockito.verifyNoMoreInteractions(appLog);
    }

    @Test
    void aFailedSignInNeverLogsANameThatIsNotALogin() throws Exception {
        signIn("my-password-in-the-wrong-box", "pw").andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(appLog).log("ACH_SYS", "WARN", "Failed sign-in for user '(unknown)'");
    }

    @Test
    void failedResendAndReceiveAreNotLoggedAsInfo() throws Exception {
        org.mockito.Mockito.when(eodFileJob.resend("F1")).thenReturn("ERROR");
        mvc.perform(post("/api/eod/files/F1/resend").with(httpBasic("achorig", "orig-pw")).with(csrf())).andExpect(status().isOk());
        org.mockito.Mockito.verify(appLog).log("ACH_ORIG", "ERROR", "User 'achorig' resent F1: ERROR");

        mvc.perform(post("/api/v1/ach/receiving/files/receive").with(httpBasic("achrecv", "recv-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sample\":\"PAYROLL\"}")).andExpect(status().isOk());
        org.mockito.Mockito.verify(appLog).log("ACH_RCV", "INFO",
                "User 'achrecv' received sample PAYROLL: PAYROLL.ach is waiting for the next processing window");
    }

    @Test
    void signOutIsLoggedForTheUser() throws Exception {
        MockHttpSession session = (MockHttpSession) signIn("achorig", "orig-pw").andReturn().getRequest().getSession();
        mvc.perform(post("/api/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        org.mockito.Mockito.verify(appLog).log("ACH_ORIG", "INFO", "User 'achorig' signed out");
    }

    @Test
    void signingOutWithoutASessionLogsNothing() throws Exception {
        mvc.perform(post("/api/logout").with(csrf())).andExpect(status().isNoContent());
        org.mockito.Mockito.verifyNoInteractions(appLog);
    }

    @Test
    void resendAndReceiveEachLogTheUser() throws Exception {
        org.mockito.Mockito.when(eodFileJob.resend("F1")).thenReturn("SENT");
        mvc.perform(post("/api/eod/files/F1/resend").with(httpBasic("achorig", "orig-pw")).with(csrf())).andExpect(status().isOk());
        org.mockito.Mockito.verify(appLog).log("ACH_ORIG", "INFO", "User 'achorig' resent F1: SENT");

        mvc.perform(post("/api/v1/ach/receiving/files/receive").with(httpBasic("achrecv", "recv-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sample\":\"PAYROLL\"}")).andExpect(status().isOk());
        org.mockito.Mockito.verify(appLog).log("ACH_RCV", "INFO",
                "User 'achrecv' received sample PAYROLL: PAYROLL.ach is waiting for the next processing window");
    }

    @Test
    void onlyComplianceResolvesOfacHitsAndOnlyOriginationOpsRunWindows() throws Exception {
        String resolve = "{\"transfer_id\":\"TRF-1\",\"resolution\":\"CLEARED_FALSE_POSITIVE\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/compliance/ofac/resolve")
                .with(httpBasic("achorig", "orig-pw")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(resolve))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.when(originationWindow.resolve("TRF-1", "CLEARED_FALSE_POSITIVE", "achcomp", null))
                .thenReturn(new com.achpayments.origination.OriginationWindow.Resolution("TRF-1", "FLAGGED", "CLEARED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/compliance/ofac/resolve")
                .with(httpBasic("achcomp", "comp-pw")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(resolve))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.current_status").value("CLEARED"));

        String trigger = "{\"window_time\":\"12:00\"}";
        mvc.perform(post("/api/v1/ach/processing/trigger-window").with(httpBasic("achcomp", "comp-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(trigger)).andExpect(status().isForbidden());
        org.mockito.Mockito.when(originationWindow.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("12:00"),
                org.mockito.ArgumentMatchers.eq("achorig")))
                .thenReturn(new com.achpayments.origination.OriginationWindow.Run("WIN-20261106-1200", "12:00", 0, 0, 0, 0, 0, 0));
        mvc.perform(post("/api/v1/ach/processing/trigger-window").with(httpBasic("achorig", "orig-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(trigger)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.window_id").value("WIN-20261106-1200"));
    }

    @Test
    void onlyReceivingOpsRunBlueprintSteps() throws Exception {
        org.mockito.Mockito.when(receivingWindow.run("09:00", null, "achrecv")).thenReturn(new com.achpayments.inbound.ReceivingWindow.Run(
                "EXEC-REC-20261106-0900", "COMPLETED", "09:00", java.time.OffsetDateTime.parse("2026-11-06T14:00:00Z"), java.util.List.of()));
        mvc.perform(post("/api/v1/ach/receiving/trigger/0900").with(httpBasic("achorig", "orig-pw")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ach/receiving/trigger/0900").with(httpBasic("achrecv", "recv-pw")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.execution_id").value("EXEC-REC-20261106-0900"));
        org.mockito.Mockito.verify(appLog).log("ACH_RCV", "INFO", "User 'achrecv' triggered processing window 09:00");
    }

    @Test
    void aRefusedTransferIsLoggedWithoutItsMessage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        verify(session, sendCode(session)).andExpect(status().isOk());
        org.mockito.Mockito.when(transfers.submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.achpayments.transfer.TransferRejected(com.achpayments.transfer.TransferRejected.Kind.FUNDS, "Your balance of $1 is low"));
        mvc.perform(post("/api/transfers").session(session).with(httpBasic(MEMBER, "member-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(appLog).log("ACH_ORIG", "WARN", "Transfer refused for user 'member' on /api/transfers: FUNDS");
    }

    @Test
    void anUnhandledErrorIsLoggedWithItsStackTrace() {
        IllegalStateException failure = new IllegalStateException("boom");
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString())).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mvc.perform(get("/api/v1/ach/back-office/receiving/returns").with(httpBasic("achrecv", "recv-pw"))));
        org.mockito.Mockito.verify(appLog).log("ACH_RCV", "ERROR",
                "Unhandled error on GET /api/v1/ach/back-office/receiving/returns: java.lang.IllegalStateException", failure);
    }

    @Test
    void aPathValueCantChangeTheSystemOfAnUnhandledError() {
        IllegalStateException failure = new IllegalStateException("boom");
        org.mockito.Mockito.when(eodFileStore.fileName("receiving-1")).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mvc.perform(get("/api/eod/files/receiving-1/content").with(httpBasic("achorig", "orig-pw"))));
        org.mockito.Mockito.verify(appLog).log("ACH_ORIG", "ERROR",
                "Unhandled error on GET /api/eod/files/receiving-1/content: java.lang.IllegalStateException", failure);
    }

    @Test
    void anAccessErrorIsNotLoggedAsUnhandled() throws Exception {
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("no")); // thrown inside the controller
        mvc.perform(get("/api/v1/ach/back-office/receiving/returns").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isForbidden());
        org.mockito.Mockito.verify(appLog, org.mockito.Mockito.never()).log(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("ERROR"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onlyAdminsCanReadTheLogs() throws Exception {
        org.mockito.Mockito.when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(0L);
        mvc.perform(get("/api/v1/admin/logs").with(httpBasic("admin", "admin-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/logs").with(httpBasic("achorig", "orig-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/logs").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/logs/1").with(httpBasic(MEMBER, "member-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void theLogFiltersRefuseBadValues() throws Exception {
        mvc.perform(get("/api/v1/admin/logs?level=NOPE").with(httpBasic("admin", "admin-pw"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/logs?page=0").with(httpBasic("admin", "admin-pw"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/logs?limit=101").with(httpBasic("admin", "admin-pw"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/logs?from=nope").with(httpBasic("admin", "admin-pw"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/logs?from=2026-11-06&to=2026-11-05").with(httpBasic("admin", "admin-pw")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("The From date can't be after the To date."));
    }

    @Test
    void theRemovedOperatorLoginCannotSignIn() throws Exception {
        mvc.perform(get("/api/me").with(httpBasic("ACHOperator", "operator-pw"))).andExpect(status().isUnauthorized());
    }

    @Test
    void achorigSeesOnlyTheOriginationScreens() throws Exception {
        mvc.perform(get("/api/eod/status").with(httpBasic("achorig", "orig-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ach/back-office/origination/returns").with(httpBasic("achorig", "orig-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ach/back-office/receiving/returns").with(httpBasic("achorig", "orig-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ach/receiving/files").with(httpBasic("achorig", "orig-pw"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ach/receiving/files/receive").with(httpBasic("achorig", "orig-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sample\":\"PAYROLL\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/params/systems").with(httpBasic("achorig", "orig-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/transfers/dates").with(httpBasic("achorig", "orig-pw"))).andExpect(status().isForbidden());
    }

    @Test
    void onlyAdminsManageBusinessRules() throws Exception {
        mvc.perform(get("/api/ach-business-rules?systemName=ACH_RCV").with(httpBasic("admin", "admin-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/ach-business-rules?systemName=ACH_RCV").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/ach-business-rules").with(httpBasic("achorig", "orig-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/ach-business-rules/1").with(httpBasic(MEMBER, "member-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        org.mockito.Mockito.when(businessRules.save(1L, null, "R", null, "p", "{", "admin"))
                .thenThrow(new IllegalArgumentException("The JSON rule isn't valid JSON."));
        mvc.perform(put("/api/ach-business-rules/1").with(httpBasic("admin", "admin-pw")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleName\":\"R\",\"pseudoCode\":\"p\",\"ruleJson\":\"{\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The JSON rule isn't valid JSON."));
    }

    @Test
    void adminsKeepTheirScreensButNotTheAchOnes() throws Exception {
        mvc.perform(get("/api/params/systems").with(httpBasic("admin", "admin-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/logs").with(httpBasic("admin", "admin-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/eod/status").with(httpBasic("admin", "admin-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ach/receiving/files").with(httpBasic("admin", "admin-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ach/receiving/transactions").with(httpBasic("admin", "admin-pw"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ach/receiving/files/receive").with(httpBasic("admin", "admin-pw")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"sample\":\"PAYROLL\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ach/back-office/origination/returns").with(httpBasic("admin", "admin-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ach/back-office/receiving/returns").with(httpBasic("admin", "admin-pw"))).andExpect(status().isForbidden());
    }

    @Test
    void anUnknownBackOfficeListIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/ach/back-office/receiving/nope").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ach/back-office/receiving/returns")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ach/back-office/receiving/returns").with(httpBasic(MEMBER, "member-pw"))).andExpect(status().isForbidden());
    }

    @Test
    void achrecvSeesOnlyTheReceivingScreens() throws Exception {
        mvc.perform(get("/api/v1/ach/back-office/receiving/returns").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ach/back-office/origination/returns").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/params/systems").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/eod/status").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/transfers/dates").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/me").with(httpBasic("achrecv", "recv-pw")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("ACH_RECV"))
                .andExpect(jsonPath("$.memberNo").doesNotExist());
    }

    @Test
    void onlyAchorigSeesTheEodStatus() throws Exception {
        mvc.perform(get("/api/eod/status").with(httpBasic(MEMBER, "member-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/eod/status").with(httpBasic("achrecv", "recv-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/eod/status")).andExpect(status().isUnauthorized());
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
