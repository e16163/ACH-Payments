package com.achpayments.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.achpayments.backoffice.BackOfficeController;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** docs/LDAP-PLAN.md: sign-in against an LDAP server loaded from ldap/seed.ldif, with Ba's permissions per role. */
@WebMvcTest({MeController.class, BackOfficeController.class})
@Import({SecurityConfig.class, MfaService.class, MemberNumbers.class, LdapLoginTest.Clocks.class})
@TestPropertySource(properties = {"app.admin-password=admin-pw", "app.orig-password=orig-pw", "app.recv-password=recv-pw",
        "app.comp-password=comp-pw", "app.member-password=member-pw"})
class LdapLoginTest {

    private static final String BASE = "dc=achpayments,dc=local";
    private static final String PASSWORD = "ldap-pw";
    private static final InMemoryDirectoryServer LDAP = startLdap();

    @TestConfiguration
    static class Clocks {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @DynamicPropertySource
    static void ldapUrl(DynamicPropertyRegistry registry) {
        registry.add("app.ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort() + "/" + BASE);
    }

    @AfterAll
    static void stopLdap() {
        LDAP.shutDown(true);
    }

    private static InMemoryDirectoryServer startLdap() {
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0));
            InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
            server.importFromLDIF(true, "ldap/seed.ldif");
            for (String user : List.of("ldapadmin", "ldaporig", "ldaprecv", "ldapcomp", "ldapmember")) {
                server.modify(person(user), new Modification(ModificationType.REPLACE, "userPassword", PASSWORD));
            }
            server.startListening();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String person(String uid) {
        return "uid=" + uid + ",ou=people," + BASE;
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JdbcTemplate jdbc;

    @MockitoBean
    AppLogRepository appLog;

    private JsonNode me(String user) throws Exception {
        String body = mvc.perform(get("/api/me").with(httpBasic(user, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void eachSeededUserGetsTheSameRoleAndPermissionsAsInSecurityConfig() throws Exception {
        for (var user : List.of(List.of("ldapadmin", "ADMIN"), List.of("ldaporig", "ACH_ORIG"), List.of("ldaprecv", "ACH_RECV"),
                List.of("ldapcomp", "COMPLIANCE"), List.of("ldapmember", "MEMBER"))) {
            JsonNode me = me(user.get(0));
            assertThat(strings(me.get("roles"))).containsExactly(user.get(1));
            assertThat(strings(me.get("permissions"))).containsExactlyInAnyOrderElementsOf(SecurityConfig.PERMISSIONS.get(user.get(1)));
        }
    }

    @Test
    void anLdapMemberGetsTheirMemberNumberFromEmployeeNumber() throws Exception {
        assertThat(me("ldapmember").get("memberNo").asString()).isEqualTo("M10982347");
        assertThat(me("ldaporig").get("memberNo").isNull()).isTrue();
    }

    @Test
    void permissionsDecideTheBackOfficeScreens() throws Exception {
        mvc.perform(get("/api/v1/ach/back-office/origination/returns").with(httpBasic("ldaporig", PASSWORD))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ach/back-office/receiving/returns").with(httpBasic("ldaporig", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ach/back-office/receiving/reversals").with(httpBasic("ldaprecv", PASSWORD))).andExpect(status().isOk());
        mvc.perform(get("/api/params/systems").with(httpBasic("ldaprecv", PASSWORD))).andExpect(status().isForbidden());
    }

    @Test
    void aUserWithTwoRolesGetsBothSetsOfPermissions() throws Exception {
        LDAP.add("dn: " + person("twohats"), "objectClass: inetOrgPerson", "uid: twohats", "cn: Two Hats", "sn: Hats",
                "userPassword: " + PASSWORD);
        for (String role : List.of("ACH_ORIG", "ACH_RECV")) {
            LDAP.modify("cn=" + role + ",ou=roles," + BASE, new Modification(ModificationType.ADD, "member", person("twohats")));
        }
        JsonNode me = me("twohats");
        assertThat(strings(me.get("roles"))).containsExactly("ACH_ORIG", "ACH_RECV");
        assertThat(strings(me.get("permissions"))).contains("ACH_ORIG_Return_UI", "ACH_RECV_Return_UI");
        mvc.perform(get("/api/v1/ach/back-office/receiving/returns").with(httpBasic("twohats", PASSWORD))).andExpect(status().isOk());
    }

    @Test
    void aWrongLdapPasswordIsRefusedAndLoggedByName() throws Exception {
        mvc.perform(post("/api/login").with(csrf()).param("username", "ldaporig").param("password", "nope"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Incorrect username or password."));
        org.mockito.Mockito.verify(appLog).log("ACH_SYS", "WARN", "Failed sign-in for user 'ldaporig'");
    }

    @Test
    void theFallbackLoginsStillWork() throws Exception {
        mvc.perform(get("/api/v1/ach/back-office/origination/returns").with(httpBasic("achorig", "orig-pw"))).andExpect(status().isOk());
    }
}
