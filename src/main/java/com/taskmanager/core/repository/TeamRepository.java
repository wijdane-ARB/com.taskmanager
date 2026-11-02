package com.taskmanager.core.repository;

import com.taskmanager.core.entity.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TeamRepository extends JpaRepository<Team, UUID> {

    @Override
    @NonNull
    @EntityGraph(attributePaths = {"owner"})
    Optional<Team> findById(@NonNull UUID id);

    @Override
    @NonNull
    @EntityGraph(attributePaths = {"owner"})
    List<Team> findAllById(@NonNull Iterable<UUID> ids);
}
