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
 * Builds a JPA Specification<Task> from a TaskFilterRequest.
 *
 * Each non-null field in the request adds one predicate to the WHERE clause.
 * Null fields are skipped — so an empty request returns all non-cancelled tasks
 * for the owner, and a fully-populated request gives a precise filtered result.
 *
 * CANCELLED tasks are always excluded regardless of the filter — they are
 * soft-deleted and must never appear in any user-facing query.
 *
 * Usage:
 *   Specification<Task> spec = TaskSpecification.of(ownerId, filter);
 *   Page<Task> page = taskRepository.findAll(spec, pageable);
 */
public class TaskSpecification {

    private TaskSpecification() {}

    public static Specification<Task> of(UUID ownerId, TaskFilterRequest filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // ── Always-on predicates ──────────────────────────────────────────

            // Ownership: never return another user's tasks
            predicates.add(cb.equal(root.get("owner").get("id"), ownerId));

            // Soft-delete guard: CANCELLED tasks are invisible regardless of filter
            predicates.add(cb.notEqual(root.get("status"), TaskStatus.CANCELLED));

            // ── Optional filter predicates ────────────────────────────────────

            if (filter.projectId() != null) {
                predicates.add(cb.equal(root.get("project").get("id"), filter.projectId()));
            }

            // If the caller explicitly asks for CANCELLED, we still block it —
            // soft-deleted tasks are never surfaced through the filter API.
            // They would have to query the RL training endpoint directly.
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

            // Keyword: case-insensitive substring match on title.
            // Uses LOWER() on both sides so the index on 'title' is bypassed —
            // acceptable for v1. For production search, use Postgres full-text
            // (tsvector/tsquery) or a dedicated search service.
            //
            // Metacharacter escaping: a raw '%' or '_' in the keyword would
            // otherwise match any character sequence or any single character,
            // turning a literal search into a wildcard. Escape them first so
            // the pattern matches exactly what the user typed.
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                String escaped = filter.keyword()
                        .replace("\\", "\\\\")   // escape the escape character first
                        .replace("%",  "\\%")    // literal percent sign
                        .replace("_",  "\\_");   // literal underscore
                String pattern = "%" + escaped.toLowerCase() + "%";
                predicates.add(cb.like(cb.lower(root.get("title")), pattern, '\\'));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}