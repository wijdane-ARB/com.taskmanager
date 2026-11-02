package com.taskmanager.core.repository;

import com.taskmanager.core.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT u.displayName FROM User u JOIN TeamMember tm ON tm.user.id = u.id WHERE u.id = :userId AND tm.team.id = :teamId")
    Optional<String> findDisplayNameIfTeamMember(@Param("userId") UUID userId, @Param("teamId") UUID teamId);
}