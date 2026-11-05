package team.projectpulse.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.team.Team;
import team.projectpulse.user.userinvitation.UserInvitationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student: T2, T7, T8, T10. */
@ExtendWith(MockitoExtension.class)
class StudentLifecycleServiceTest {
    @Mock StudentRepository studentRepository;
    @Mock SectionRepository sectionRepository;
    @Mock UserInvitationService userInvitationService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock UserUtils userUtils;
    @InjectMocks StudentService service;

    @Test
    void deactivationAndAuthorizedRepeatRetainProfileAndMembership() {
        Student student = student(true);
        Section section = new Section();
        Team team = new Team();
        student.setSection(section);
        student.setTeam(team);
        given(userUtils.hasRole("ROLE_instructor")).willReturn(true);
        given(userUtils.getUserId()).willReturn(9);
        given(studentRepository.findLifecycleStudentForUpdate(3, 9)).willReturn(Optional.of(student));
        given(studentRepository.save(student)).willReturn(student);
        assertThat(service.deactivateStudent(3)).isSameAs(student);
        assertThat(service.deactivateStudent(3)).isSameAs(student);
        assertThat(student.isEnabled()).isFalse();
        assertThat(student.getSection()).isSameAs(section);
        assertThat(student.getTeam()).isSameAs(team);
        assertThat(student.getFirstName()).isEqualTo("Before");
    }

    @Test
    void unauthorizedDirectCallCannotProbeOrDeactivate() {
        assertThatThrownBy(() -> service.deactivateStudent(3)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(studentRepository);
    }

    @Test
    void inaccessibleOrMissingStudentRefusedBeforeIdempotency() {
        given(userUtils.hasRole(anyString())).willAnswer(invocation -> invocation.getArgument(0).equals("ROLE_admin"));
        given(userUtils.getUserId()).willReturn(9);
        given(studentRepository.findLifecycleStudentForUpdate(3, 9)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateStudent(3)).isInstanceOf(AccessDeniedException.class);
        verify(studentRepository, never()).save(any());
        verify(studentRepository, never()).findById(any());
    }

    @Test
    void genericUpdateCannotDeactivateOrPartiallyMutateProfile() {
        Student current = lockedStudent(true);
        assertThatThrownBy(() -> service.updateStudent(3, update(false), true)).isInstanceOf(IllegalArgumentException.class);
        assertUnchanged(current, true);
    }

    @Test
    void staleActiveFormCannotReactivateOrPartiallyMutateProfile() {
        Student current = lockedStudent(false);
        assertThatThrownBy(() -> service.updateStudent(3, update(true), true))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertUnchanged(current, false);
    }

    @Test
    void reactivationRequiresExplicitInactiveBaseline() {
        Student current = lockedStudent(false);
        given(userUtils.hasRole("ROLE_instructor")).willReturn(true);
        assertThatThrownBy(() -> service.updateStudent(3, student(true)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertUnchanged(current, false);
        given(studentRepository.save(current)).willReturn(current);
        assertThat(service.updateStudent(3, student(true), false).isEnabled()).isTrue();
    }

    @Test
    void legacyProfileOnlySavePreservesInactiveStatus() {
        Student current = lockedStudent(false);
        given(userUtils.hasRole("ROLE_instructor")).willReturn(true);
        given(studentRepository.save(current)).willReturn(current);
        assertThat(service.updateStudent(3, student(false)).isEnabled()).isFalse();
    }

    private Student update(boolean enabled) {
        Student update = student(enabled);
        update.setUsername("changed");
        update.setFirstName("Changed");
        update.setEmail("changed@abc.edu");
        return update;
    }
    private Student lockedStudent(boolean enabled) {
        Student current = student(enabled);
        given(studentRepository.findByIdForUpdate(3)).willReturn(Optional.of(current));
        return current;
    }
    private Student student(boolean enabled) {
        return new Student("before", "Before", "Name", "before@abc.edu", "123456", enabled, "student");
    }
    private void assertUnchanged(Student current, boolean enabled) {
        assertThat(current.isEnabled()).isEqualTo(enabled);
        assertThat(current.getFirstName()).isEqualTo("Before");
        assertThat(current.getUsername()).isEqualTo("before");
        assertThat(current.getEmail()).isEqualTo("before@abc.edu");
        verify(studentRepository, never()).save(any());
    }
}
