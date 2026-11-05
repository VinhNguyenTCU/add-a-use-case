package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.*;
import team.projectpulse.notification.*;
import team.projectpulse.section.Section;
import team.projectpulse.system.EmailService;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student T1/T2/T4/T7/T8/T10/T11 at the shared service boundary. */
class StudentLifecycleIntegrationTest extends AbstractIntegrationTest {
    @Autowired StudentService service;
    @Autowired StudentSecurityService security;
    @Autowired StudentRepository students;
    @Autowired EvaluationService evaluations;
    @Autowired PeerEvaluationRepository evaluationRepository;
    @Autowired SubmissionStatusService submissions;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void authorizedDeactivationRetainsActualWorkMembershipAndHistory() {
        Student john = student("j.smith@abc.edu");
        int sectionId = john.getSection().getSectionId();
        int teamId = john.getTeam().getTeamId();
        long activities = activityCount(john);
        assertThat(activities).isPositive();
        List<PeerEvaluation> received = evaluationRepository.findByWeekAndEvaluateeId("2023-W32", john.getId());
        assertThat(received).isNotEmpty();
        var recorded = evaluationSnapshot(received);
        authenticate(john.getSection().getCourse().getCourseAdmin().getId(), "admin", "instructor");
        assertThat(security.canDeactivateStudent(john.getId())).isTrue();
        service.deactivateStudent(john.getId());
        service.deactivateStudent(john.getId());
        em.flush(); em.clear();
        Student retained = students.findById(john.getId()).orElseThrow();
        assertThat(retained.isEnabled()).isFalse();
        assertThat(retained.getSection().getSectionId()).isEqualTo(sectionId);
        assertThat(retained.getTeam().getTeamId()).isEqualTo(teamId);
        assertThat(activityCount(retained)).isEqualTo(activities);
        List<PeerEvaluation> after = evaluationRepository.findByWeekAndEvaluateeId("2023-W32", retained.getId());
        assertThat(evaluationSnapshot(after)).isEqualTo(recorded);
        assertThat(student("e.hudson@abc.edu").isEnabled()).isTrue();
        assertThat(reportIds(sectionId, "2023-W32")).contains(retained.getId());
        assertThat(reportIds(sectionId, "2023-W40")).doesNotContain(retained.getId());
        retained.setTeam(null); em.flush();
        assertThat(reportIds(sectionId, "2023-W32")).contains(retained.getId());
        assertThat(evaluations.generateWeeklyPeerEvaluationReportForSection(sectionId, "2023-W32")
                .getPeerEvaluationAverages().stream().filter(a -> a.getStudentId().equals(retained.getId())).findFirst().orElseThrow().getTeamName()).isNull();
    }

    @Test
    void inactiveCreationIsNotPersistedAndActiveCreationStillWorks() {
        Student woody = student("w.allen@abc.edu");
        Student amanda = student("a.wagner@abc.edu");
        PeerEvaluation accepted = new PeerEvaluation("2023-W32", woody, amanda, List.of(), "active target", "private");
        assertThat(evaluations.addPeerEvaluation(accepted).getPeerEvaluationId()).isNotNull();
        Student eric = student("e.hudson@abc.edu");
        authenticate(eric.getSection().getCourse().getCourseAdmin().getId(), "admin", "instructor");
        service.deactivateStudent(eric.getId());
        long count = evaluationRepository.count();
        PeerEvaluation refused = new PeerEvaluation("2023-W32", student("j.smith@abc.edu"), eric, List.of(), "refused", "refused");
        assertThatThrownBy(() -> evaluations.addPeerEvaluation(refused))
                .isInstanceOf(team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException.class)
                .hasMessageContaining("active student");
        assertThat(evaluationRepository.count()).isEqualTo(count);
    }

    @Test
    void inactiveEvaluateeRetainsOpenWindowEditsAndClosedWindowRefusal() {
        Student eric = student("e.hudson@abc.edu");
        Student john = student("j.smith@abc.edu");
        authenticate(eric.getSection().getCourse().getCourseAdmin().getId(), "admin", "instructor");
        service.deactivateStudent(eric.getId());
        PeerEvaluation stored = evaluationRepository.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W32", john.getId(), eric.getId()).orElseThrow();
        int id = stored.getPeerEvaluationId();
        double score = stored.getTotalScore();
        PeerEvaluation request = new PeerEvaluation("2023-W32", john, eric,
                stored.getRatings().stream().map(r -> new Rating(r.getCriterion(), r.getActualScore())).toList(),
                "Edited retained work", "Edited private");
        authenticate(john.getId(), "student");
        PeerEvaluation updated = evaluations.updatePeerEvaluation(id, request);
        assertThat(updated.getPeerEvaluationId()).isEqualTo(id);
        assertThat(updated.getPublicComment()).isEqualTo("Edited retained work");
        assertThat(updated.getTotalScore()).isEqualTo(score);
        em.flush();
        PeerEvaluation closed = evaluationRepository.findByWeekAndEvaluatorIdAndEvaluateeId("2023-W31", john.getId(), eric.getId()).orElseThrow();
        String oldComment = closed.getPublicComment();
        assertThatThrownBy(() -> evaluations.updatePeerEvaluation(closed.getPeerEvaluationId(), request))
                .isInstanceOf(team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException.class)
                .hasMessageContaining("closed");
        assertThat(closed.getPublicComment()).isEqualTo(oldComment);
    }

    @Test
    void ownerWithoutSectionAssignmentAndAssignedInstructorAllowedButOthersRefused() {
        Student john = student("j.smith@abc.edu");
        Section section = john.getSection();
        int ownerId = section.getCourse().getCourseAdmin().getId();
        section.getInstructors().removeIf(i -> i.getId().equals(ownerId));
        em.flush();
        authenticate(ownerId, "admin", "instructor");
        assertThat(security.canDeactivateStudent(john.getId())).isTrue();
        service.deactivateStudent(john.getId());
        var assigned = em.createQuery("select i from Instructor i where i.id <> :owner", team.projectpulse.instructor.Instructor.class)
                .setParameter("owner", ownerId).setMaxResults(1).getSingleResult();
        section.addInstructor(assigned); em.flush();
        authenticate(assigned.getId(), "instructor");
        assertThat(security.canDeactivateStudent(john.getId())).isTrue();
        service.deactivateStudent(john.getId());
        section.getInstructors().remove(assigned); em.flush();
        assertThat(security.canDeactivateStudent(john.getId())).isFalse();
        assertThatThrownBy(() -> service.deactivateStudent(john.getId())).isInstanceOf(AccessDeniedException.class);
        authenticate(assigned.getId(), "admin", "instructor");
        assertThatThrownBy(() -> service.deactivateStudent(john.getId())).isInstanceOf(AccessDeniedException.class);
        authenticate(john.getId(), "student");
        assertThatThrownBy(() -> service.deactivateStudent(john.getId())).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.clearContext();
        assertThat(security.canDeactivateStudent(john.getId())).isFalse();
        assertThatThrownBy(() -> service.deactivateStudent(john.getId())).isInstanceOf(AccessDeniedException.class);
        authenticate(ownerId, "admin");
        assertThatThrownBy(() -> service.deactivateStudent(Integer.MAX_VALUE)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void teamlessStudentDeactivationAndReminderEligibilityReuseExistingRules() {
        Student jerry = student("j.moon@abc.edu");
        Section section = jerry.getSection();
        authenticate(section.getCourse().getCourseAdmin().getId(), "admin", "instructor");
        service.deactivateStudent(jerry.getId());
        em.createQuery("delete from Rating r where r.peerEvaluation.evaluatee = :student").setParameter("student", jerry).executeUpdate();
        em.createQuery("delete from PeerEvaluation e where e.evaluatee = :student").setParameter("student", jerry).executeUpdate();
        var owed = submissions.findOwedItems(section.getSectionId(), List.of(SubmittableItem.WEEKLY_ACTIVITY_REPORT, SubmittableItem.PEER_EVALUATION), "2023-W32");
        assertThat(owed.keySet()).extracting(Student::getEmail).doesNotContain("j.moon@abc.edu", "j.smith@abc.edu", "e.hudson@abc.edu").contains("w.allen@abc.edu");
        EmailService email = mock(EmailService.class);
        new ReminderService(email, java.time.Clock.systemUTC()).remind(section, owed, "2023-W32");
        verify(email, never()).sendReminderEmail(eq(jerry.getEmail()), anyString(), anyString());
        verify(email, atLeastOnce()).sendReminderEmail(eq("w.allen@abc.edu"), anyString(), anyString());
        Student teamless = em.createQuery("select s from Student s where s.section = :section and s.team is null", Student.class)
                .setParameter("section", section).setMaxResults(1).getSingleResult();
        service.deactivateStudent(teamless.getId());
        assertThat(teamless.isEnabled()).isFalse();
        assertThat(teamless.getSection()).isSameAs(section);
        assertThat(teamless.getTeam()).isNull();
        assertThat(reportIds(section.getSectionId(), "2023-W32")).doesNotContain(teamless.getId());
    }

    @Test
    void committedStaleProfileSaveRefusedAndRowLockSerializesProfileThenDeactivation() throws Exception {
        Student seed = student("j.smith@abc.edu");
        int sectionId = seed.getSection().getSectionId();
        int ownerId = seed.getSection().getCourse().getCourseAdmin().getId();
        TestTransaction.end(); // independent committed transactions, with explicit cleanup below
        TransactionTemplate tx = new TransactionTemplate(transactions);
        int id = tx.execute(status -> {
            Student temporary = new Student("lifecycle-lock-test", "Original", "Student", "lifecycle-lock-test@abc.edu", "123456", true, "student");
            temporary.setSection(em.find(Section.class, sectionId));
            em.persist(temporary); em.flush(); return temporary.getId();
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            authenticate(ownerId, "admin", "instructor");
            service.deactivateStudent(id);
            Student stale = new Student("stale", "Stale", "Student", "stale@abc.edu", "", true, "student");
            assertThatThrownBy(() -> service.updateStudent(id, stale, true)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            String retainedName = tx.execute(status -> students.findById(id).orElseThrow().getFirstName());
            assertThat(retainedName).isEqualTo("Original");
            service.updateStudent(id, stale, false); // explicit reactivation remains supported
            CountDownLatch attempting = new CountDownLatch(1);
            Future<?>[] pending = new Future<?>[1];
            tx.executeWithoutResult(status -> {
                Student locked = students.findByIdForUpdate(id).orElseThrow();
                locked.setFirstName("Concurrent profile");
                pending[0] = executor.submit(() -> {
                    authenticate(ownerId, "admin", "instructor");
                    attempting.countDown();
                    try { service.deactivateStudent(id); } finally { SecurityContextHolder.clearContext(); }
                });
                try {
                    assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> pending[0].get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } catch (InterruptedException ex) { throw new RuntimeException(ex); }
            });
            pending[0].get(10, TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> {
                Student result = students.findById(id).orElseThrow();
                assertThat(result.isEnabled()).isFalse();
                assertThat(result.getFirstName()).isEqualTo("Concurrent profile");
            });
        } finally {
            executor.shutdownNow();
            tx.executeWithoutResult(status -> students.deleteById(id));
        }
    }

    private java.util.Map<Integer, List<Object>> evaluationSnapshot(List<PeerEvaluation> records) {
        return records.stream().collect(java.util.stream.Collectors.toMap(PeerEvaluation::getPeerEvaluationId,
                e -> java.util.Arrays.asList(e.getTotalScore(), e.getPublicComment(), e.getPrivateComment(),
                        e.getRatings().stream().collect(java.util.stream.Collectors.toMap(Rating::getRatingId, Rating::getActualScore)))));
    }
    private Student student(String email) {
        return em.createQuery("select s from Student s where s.email = :email", Student.class).setParameter("email", email).getSingleResult();
    }
    private long activityCount(Student student) {
        return em.createQuery("select count(a) from Activity a where a.student = :student", Long.class).setParameter("student", student).getSingleResult();
    }
    private List<Integer> reportIds(int section, String week) {
        return evaluations.generateWeeklyPeerEvaluationReportForSection(section, week).getPeerEvaluationAverages().stream().map(PeerEvaluationAverage::getStudentId).toList();
    }
    private void authenticate(int id, String... roles) {
        Jwt jwt = Jwt.withTokenValue("service-test").header("alg", "RS256").claim("userId", id).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                AuthorityUtils.createAuthorityList(java.util.Arrays.stream(roles).map(r -> "ROLE_" + r).toArray(String[]::new))));
    }
}
