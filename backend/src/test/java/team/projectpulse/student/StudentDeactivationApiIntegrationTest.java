package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.*;
import team.projectpulse.evaluation.dto.*;
import team.projectpulse.student.dto.StudentDto;
import team.projectpulse.section.Section;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UC-STU-deactivate-student T1/T3/T5/T7/T8/T10 through real HTTP authorization and conversion. */
class StudentDeactivationApiIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired EntityManager em;
    @Autowired StudentRepository students;
    @Autowired PeerEvaluationRepository evaluations;

    @Test
    void owningAdminAndAssignedInstructorCanDeactivateAndRepeat() throws Exception {
        String admin = login("b.wei@abc.edu");
        String instructor = login("b.gates@abc.edu");
        deactivate(4, admin);
        deactivate(4, instructor);
        assertThat(students.findById(4).orElseThrow().getTeam()).isNotNull();
        assertThat(students.findById(5).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void ownerWithoutAssignmentAllowedAndUnauthorizedInactiveRepeatRefused() throws Exception {
        String owner = login("b.wei@abc.edu");
        String assigned = login("b.gates@abc.edu");
        String unrelated = login("t.cook@abc.edu");
        Section section = students.findById(4).orElseThrow().getSection();
        section.getInstructors().removeIf(i -> i.getId().equals(1));
        em.flush();
        // T1/T8: the owner must be able to load the form before confirming deactivation.
        mvc.perform(get("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(4));
        mvc.perform(get("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, unrelated))
                .andExpect(status().isForbidden());
        deactivate(4, owner);
        section.getInstructors().removeIf(i -> i.getId().equals(2));
        em.flush();
        for (String token : List.of(assigned, unrelated)) {
            mvc.perform(post("/api/v1/students/4/deactivate").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void genericPutGuardsStatusBeforeProfileMutationAndAllowsExplicitReactivation() throws Exception {
        String admin = login("b.wei@abc.edu");
        String profile = json.writeValueAsString(new StudentDto(4, "changed@abc.edu", "Changed", "Name", "changed@abc.edu", false, null, null, null, null, null));
        mvc.perform(put("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertThat(students.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
        deactivate(4, admin);
        String active = json.writeValueAsString(new StudentDto(4, "changed@abc.edu", "Changed", "Name", "changed@abc.edu", true, null, null, null, null, null));
        mvc.perform(put("/api/v1/students/4?expectedEnabled=true").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(active))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        mvc.perform(put("/api/v1/students/4").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(active))
                .andExpect(status().isConflict());
        assertThat(students.findById(4).orElseThrow().getFirstName()).isEqualTo("John");
        assertThat(students.findById(4).orElseThrow().isEnabled()).isFalse();
        mvc.perform(put("/api/v1/students/4?expectedEnabled=false").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(active))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(put("/api/v1/students/4?expectedEnabled=true").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON).content(active))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.firstName").value("Changed"));
    }

    @Test
    void directInactiveEvaluationCreationRefusedWhileStoredOpenWindowEditSurvives() throws Exception {
        String admin = login("b.wei@abc.edu");
        String john = login("j.smith@abc.edu");
        deactivate(5, admin);
        PeerEvaluation stored = evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W32", 4, 5).orElseThrow();
        List<RatingDto> ratings = stored.getRatings().stream().map(r -> new RatingDto(null, r.getCriterion().getCriterionId(), r.getActualScore())).toList();
        PeerEvaluationDto request = new PeerEvaluationDto(null, "2023-W32", 4, null, 5, null, ratings, null, "Retained edited", "Private retained", null, null);
        long before = evaluations.count();
        mvc.perform(post("/api/v1/evaluations").header(HttpHeaders.AUTHORIZATION, john).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("A new evaluation may target only an active student."));
        assertThat(evaluations.count()).isEqualTo(before);
        mvc.perform(get("/api/v1/evaluations/evaluators/4/week/2023-W32").header(HttpHeaders.AUTHORIZATION, john))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.evaluateeId == 5)]").isNotEmpty());
        mvc.perform(put("/api/v1/evaluations/" + stored.getPeerEvaluationId()).header(HttpHeaders.AUTHORIZATION, john).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.publicComment").value("Retained edited"));
        PeerEvaluation closed = evaluations.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W31", 4, 5).orElseThrow();
        mvc.perform(put("/api/v1/evaluations/" + closed.getPeerEvaluationId()).header(HttpHeaders.AUTHORIZATION, john).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("The submission window for this peer evaluation has closed, so it can no longer be changed."));
    }

    private String login(String email) throws Exception {
        String response = mvc.perform(post("/api/v1/users/login").with(httpBasic(email, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).path("data").path("token").asText();
    }
    private void deactivate(int id, String token) throws Exception {
        mvc.perform(post("/api/v1/students/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }
}
