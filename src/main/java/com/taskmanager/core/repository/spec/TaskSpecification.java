package com.taskmanager.core.repository.spec;

import com.taskmanager.core.entity.Task;
import com.taskmanager.core.enums.TaskStatus;
import com.taskmanager.core.dto.request.TaskFilterRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CHANGED: the always-on predicate no longer filters by t.owner.id — under
 * the team model, any member of a project's team can see every task in
 * that project, not just tasks they personally created. The predicate now
 * filters by t.project.team.id IN (:callerTeamIds), where callerTeamIds is
 * every team the caller belongs to (resolved by the service beforehand).
 */
public class TaskSpecification {

    private TaskSpecification() {}

    public static Specification<Task> of(List<UUID> callerTeamIds, TaskFilterRequest filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("project", jakarta.persistence.criteria.JoinType.LEFT);
            }

            if (filter.teamId() != null) {
                predicates.add(cb.equal(root.get("project").get("team").get("id"), filter.teamId()));
            }
            // SECURITY FIX: ALWAYS constrain by caller's teams
            predicates.add(root.get("project").get("team").get("id").in(callerTeamIds));

            predicates.add(cb.notEqual(root.get("status"), TaskStatus.CANCELLED));

            if (filter.projectId() != null) {
                predicates.add(cb.equal(root.get("project").get("id"), filter.projectId()));
            }
            if (filter.status() != null && filter.status() != TaskStatus.CANCELLED) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.priority() != null) {
                predicates.add(cb.equal(root.get("priorityLevel"), filter.priority()));
            }
            if (filter.dueBefore() != null) {
                predicates.add(cb.lessThan(root.get("dueDate"), filter.dueBefore()));
            }
            if (filter.dueAfter() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dueDate"), filter.dueAfter()));
            }
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                String escaped = filter.keyword()
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + escaped.toLowerCase() + "%", '\\'));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}