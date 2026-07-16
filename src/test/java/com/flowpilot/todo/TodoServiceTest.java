package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {
    @Mock OwnedTodoRepository repository;
    private TodoService service;

    @BeforeEach
    void setUp() {
        service = new TodoService(repository);
    }

    @Test
    void getUsesOwnerRestrictedLookup() {
        UUID id = UUID.randomUUID();
        Todo todo = todo(0);
        when(repository.findByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(todo));

        assertThat(service.get("owner-a", id)).isSameAs(todo);
        verify(repository).findByIdAndOwnerId(id, "owner-a");
    }

    @Test
    void inaccessibleTodoIsConvertedToNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerId(id, "owner-b")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("owner-b", id))
            .isInstanceOf(TodoNotFound.class);
    }

    @Test
    void staleExpectedVersionIsRejectedBeforeSaving() {
        UUID id = UUID.randomUUID();
        Todo todo = todo(3);
        when(repository.findByIdAndOwnerId(id, "owner")).thenReturn(Optional.of(todo));

        assertThatThrownBy(() -> service.patch("owner", id, 2, patch()))
            .isInstanceOf(VersionConflict.class);
        verifyNoInteractionsAfterLookup(id);
    }

    @Test
    void persistenceRaceDuringPatchIsConvertedToVersionConflict() {
        UUID id = UUID.randomUUID();
        Todo todo = todo(3);
        when(repository.findByIdAndOwnerId(id, "owner")).thenReturn(Optional.of(todo));
        when(repository.saveOwned(todo))
            .thenThrow(new OptimisticLockingFailureException("concurrent update"));

        assertThatThrownBy(() -> service.patch("owner", id, 3, patch()))
            .isInstanceOf(VersionConflict.class);
    }

    @Test
    void persistenceRaceDuringDeleteIsConvertedToVersionConflict() {
        UUID id = UUID.randomUUID();
        Todo todo = todo(3);
        when(repository.findByIdAndOwnerId(id, "owner")).thenReturn(Optional.of(todo));
        doThrow(new OptimisticLockingFailureException("concurrent delete"))
            .when(repository).deleteOwned(todo);

        assertThatThrownBy(() -> service.delete("owner", id, 3))
            .isInstanceOf(VersionConflict.class);
    }

    private void verifyNoInteractionsAfterLookup(UUID id) {
        verify(repository).findByIdAndOwnerId(id, "owner");
        verifyNoInteractions(repository);
    }

    private static Todo todo(long version) {
        Todo todo = new Todo("owner", "title", null, TodoStatus.TODO, null);
        todo.version = version;
        return todo;
    }

    private static TodoPatch patch() {
        return new TodoPatch("changed", false, null, null, false, null);
    }
}
