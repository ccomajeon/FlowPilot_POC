package com.flowpilot.todo;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BoardService {
    private final BoardStore store;
    private final BoardMetrics metrics;

    BoardService(BoardStore store, BoardMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    Page<Board> list(boolean includeInactive, boolean admin, int page, int size) {
        if (includeInactive && !admin) throw new BoardAccessDenied();
        validatePage(page, size);
        Sort sort = Sort.by(Sort.Direction.ASC, "displayOrder").and(Sort.by(Sort.Direction.ASC, "id"));
        return store.boards(includeInactive, PageRequest.of(page, size, sort));
    }

    @Transactional(readOnly = true)
    Board get(UUID id, boolean admin) {
        Board board = store.findBoard(id).orElseThrow(BoardNotFound::new);
        if (!board.active && !admin) throw new BoardNotFound();
        return board;
    }

    @Transactional
    Board create(String actor, BoardCreate request) {
        Board board = new Board(actor, request.name(), request.description(), request.displayOrder(),
            Boolean.TRUE.equals(request.active()));
        try {
            Board result = store.saveNew(board);
            metrics.record("board_create", "success", "none");
            return result;
        } catch (DataIntegrityViolationException exception) {
            metrics.record("board_create", "rejected", "name_conflict");
            throw new BoardNameConflict();
        }
    }

    @Transactional
    Board patch(String actor, UUID id, long expectedVersion, BoardPatch patch) {
        Board board = store.findBoard(id).orElseThrow(BoardNotFound::new);
        if (board.version != expectedVersion) throw new VersionConflict();
        board.patch(actor, patch);
        try {
            Board result = store.saveChanged(board);
            metrics.record("board_patch", "success", "none");
            return result;
        } catch (OptimisticLockingFailureException exception) {
            metrics.record("board_patch", "rejected", "version_conflict");
            throw new VersionConflict();
        } catch (DataIntegrityViolationException exception) {
            metrics.record("board_patch", "rejected", "name_conflict");
            throw new BoardNameConflict();
        }
    }

    static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequest("page/size is invalid");
    }
}

class BoardNotFound extends RuntimeException {}
class BoardNameConflict extends RuntimeException {}
class BoardAccessDenied extends RuntimeException {}
