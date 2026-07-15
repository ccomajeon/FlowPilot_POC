package com.flowpilot.todo;

import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface TodoRepository extends JpaRepository<Todo, UUID>, JpaSpecificationExecutor<Todo> {
    Optional<Todo> findByIdAndOwnerId(UUID id, String ownerId);

    default Page<Todo> owned(String owner, TodoStatus status, LocalDate from, LocalDate to, Pageable page) {
        return findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("ownerId"), owner));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("dueDate"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("dueDate"), to));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, page);
    }
}
