package com.flowpilot.todo;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TodoService {
    private final TodoRepository repository;
    TodoService(TodoRepository repository) { this.repository = repository; }

    @Transactional
    Todo create(String owner, TodoCreate request) {
        return repository.save(new Todo(owner, request.title(), request.description(), request.status(), request.dueDate()));
    }

    @Transactional(readOnly=true)
    Todo get(String owner, UUID id) { return owned(owner, id); }

    @Transactional(readOnly=true)
    Page<Todo> list(String owner, TodoStatus status, LocalDate from, LocalDate to, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) throw new BadRequest("dueFrom must not be after dueTo");
        if (page < 0 || size < 1 || size > 100) throw new BadRequest("page/size is invalid");
        return repository.owned(owner, status, from, to,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    @Transactional
    Todo patch(String owner, UUID id, long expected, TodoPatch patch) {
        Todo todo = owned(owner, id);
        if (todo.version != expected) throw new VersionConflict();
        todo.patch(patch);
        return repository.saveAndFlush(todo);
    }

    @Transactional
    void delete(String owner, UUID id, long expected) {
        Todo todo = owned(owner, id);
        if (todo.version != expected) throw new VersionConflict();
        repository.delete(todo);
        repository.flush();
    }

    private Todo owned(String owner, UUID id) {
        return repository.findByIdAndOwnerId(id, owner).orElseThrow(TodoNotFound::new);
    }
}
class TodoNotFound extends RuntimeException {}
class VersionConflict extends RuntimeException {}
class PreconditionRequired extends RuntimeException {}
class BadRequest extends RuntimeException { BadRequest(String message) { super(message); } }
