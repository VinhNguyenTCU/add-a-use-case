package team.projectpulse.security;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.student.StudentService;
import team.projectpulse.user.PeerEvaluationUser;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UC-STU-deactivate-student T6: real signed tokens, default checks, and committed account state. */
class ActiveAccountAuthenticationIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired StudentService students;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void issuedStudentTokenLosesAccessAfterCommittedDeactivationAndLoginIsRefused() throws Exception {
        String studentToken = login("j.smith@abc.edu");
        String adminToken = login("b.wei@abc.edu");
        TestTransaction.end();
        try {
            mvc.perform(get("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true));
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(adminToken),
                    AuthorityUtils.createAuthorityList("ROLE_admin", "ROLE_instructor")));
            students.deactivateStudent(4); // the service transaction commits before the next request
            SecurityContextHolder.clearContext();
            mvc.perform(get("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false));
            mvc.perform(post("/api/v1/users/login").with(httpBasic("j.smith@abc.edu", "123456")))
                    .andExpect(status().isUnauthorized());
        } finally { enable(4); }
    }

    @Test
    void disabledInstructorAlsoLosesTokenAccessAndCannotLogin() throws Exception {
        String token = login("b.wei@abc.edu");
        TestTransaction.end();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(s -> em.find(PeerEvaluationUser.class, 1).setEnabled(false));
            mvc.perform(get("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/users/login").with(httpBasic("b.wei@abc.edu", "123456")))
                    .andExpect(status().isUnauthorized());
        } finally { enable(1); }
    }

    @Test
    void decoderRetainsExpiryAndSignatureChecksAndRejectsMissingMalformedOrUnknownAccount() throws Exception {
        String active = login("j.smith@abc.edu");
        mvc.perform(get("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + active)).andExpect(status().isOk());
        String[] parts = active.split("\\.");
        String badSignature = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("a") ? "b" : "a") + parts[2].substring(1);
        unauthorized(badSignature);
        unauthorized(signedToken(4, Instant.now().minusSeconds(180)));
        unauthorized(signedToken(null, Instant.now().plusSeconds(300)));
        unauthorized(signedToken("malformed", Instant.now().plusSeconds(300)));
        unauthorized(signedToken(Integer.MAX_VALUE, Instant.now().plusSeconds(300)));
    }

    @Test
    void lifecycleRouteRefusesStudentsAnonymousMalformedAndMissingTargets() throws Exception {
        String student = login("j.smith@abc.edu");
        String admin = login("b.wei@abc.edu");
        mvc.perform(post("/api/v1/students/4/deactivate")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/students/4/deactivate").header(HttpHeaders.AUTHORIZATION, "Bearer " + student)).andExpect(status().isForbidden());
        for (String id : new String[]{"not-an-id", "2147483647"}) {
            mvc.perform(post("/api/v1/students/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.flag").value(false));
        }
    }

    private void enable(int id) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> em.find(PeerEvaluationUser.class, id).setEnabled(true));
    }
    private String login(String email) throws Exception {
        String response = mvc.perform(post("/api/v1/users/login").with(httpBasic(email, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("token").asText();
    }
    private String signedToken(Object id, Instant expires) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("j.smith@abc.edu").issuedAt(Instant.now().minusSeconds(600))
                .expiresAt(expires).claim("authorities", "ROLE_student");
        if (id != null) claims.claim("userId", id);
        return encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }
    private void unauthorized(String token) throws Exception {
        mvc.perform(get("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.flag").value(false));
    }
}
