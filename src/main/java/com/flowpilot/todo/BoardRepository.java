package com.flowpilot.todo;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BoardStore {
    Optional<Board> findBoard(UUID id);
    Optional<Board> lockForPostCreation(UUID id);
    Page<Board> boards(boolean includeInactive, Pageable pageable);
    Board saveNew(Board board);
    Board saveChanged(Board board);
}

interface BoardRepository extends JpaRepository<Board, UUID>, BoardStore {
    @Override
    default Optional<Board> findBoard(UUID id) {
        return findById(id);
    }

    @Override
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from Board b where b.id = :id")
    Optional<Board> lockForPostCreation(@Param("id") UUID id);

    @Override
    default Page<Board> boards(boolean includeInactive, Pageable pageable) {
        return includeInactive ? findAll(pageable) : findByActiveTrue(pageable);
    }

    Page<Board> findByActiveTrue(Pageable pageable);

    @Override
    default Board saveNew(Board board) {
        return saveAndFlush(board);
    }

    @Override
    default Board saveChanged(Board board) {
        return saveAndFlush(board);
    }
}
