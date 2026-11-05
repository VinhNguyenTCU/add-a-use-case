package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    // Scope is part of the lookup, including for repeat deactivation requests.
    @Query("""
            select s from Student s
            join s.section section
            join section.course course
            join course.courseAdmin owner
            where s.id = :studentId
              and (owner.id = :callerId or exists
                   (select i.id from Section assigned join assigned.instructors i
                    where assigned = section and i.id = :callerId))
            """)
    Optional<Student> findLifecycleStudent(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Student s
            join s.section section
            join section.course course
            join course.courseAdmin owner
            where s.id = :studentId
              and (owner.id = :callerId or exists
                   (select i.id from Section assigned join assigned.instructors i
                    where assigned = section and i.id = :callerId))
            """)
    Optional<Student> findLifecycleStudentForUpdate(@Param("studentId") Integer studentId, @Param("callerId") Integer callerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Student s where s.id = :studentId")
    Optional<Student> findByIdForUpdate(@Param("studentId") Integer studentId);

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);
}
