package com.flowpilot.todo;

import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface OwnedTodoRepository {
    Optional<Todo> findByIdAndOwnerId(UUID id, String ownerId);
    Page<Todo> owned(String owner, TodoStatus status, LocalDate from, LocalDate to, Pageable page);
    Todo saveNew(Todo todo);
    Todo saveOwned(Todo todo);
    void deleteOwned(Todo todo);
}

interface TodoRepository extends JpaRepository<Todo, UUID>, JpaSpecificationExecutor<Todo>, OwnedTodoRepository {
    @Override
    default Todo saveNew(Todo todo) {
        return save(todo);
    }

    @Override
    default Todo saveOwned(Todo todo) {
        return saveAndFlush(todo);
    }

    @Override
    default void deleteOwned(Todo todo) {
        delete(todo);
        flush();
    }

    @Override
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
