package com.flowpilot.todo;

import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BoardPostService {
    private final BoardStore boardStore;
    private final BoardPostStore postStore;
    private final BoardContentPolicy contentPolicy;
    private final BoardMetrics metrics;

    BoardPostService(BoardStore boardStore, BoardPostStore postStore,
            BoardContentPolicy contentPolicy, BoardMetrics metrics) {
        this.boardStore = boardStore;
        this.postStore = postStore;
        this.contentPolicy = contentPolicy;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    Page<BoardPost> list(UUID boardId, int page, int size, String sortValue) {
        BoardService.validatePage(page, size);
        Board board = boardStore.findBoard(boardId).orElseThrow(BoardNotFound::new);
        if (!board.active) throw new BoardNotFound();
        String[] parts = sortValue.split(",", -1);
        if (parts.length != 2 || !"createdAt".equals(parts[0])) throw new BadRequest("sort field is invalid");
        Sort.Direction direction;
        try {
            direction = Sort.Direction.fromString(parts[1]);
        } catch (IllegalArgumentException exception) {
            throw new BadRequest("sort direction is invalid");
        }
        Sort sort = Sort.by(direction, "createdAt").and(Sort.by(direction, "id"));
        return postStore.visiblePosts(boardId, PageRequest.of(page, size, sort));
    }

    @Transactional(readOnly = true)
    BoardPost get(UUID postId) {
        return postStore.findVisible(postId).orElseThrow(BoardPostNotFound::new);
    }

    @Transactional
    BoardPost create(String author, UUID boardId, BoardPostCreate request) {
        validateContent("post_create", request.editorType(), request.content());
        Board board = boardStore.lockForPostCreation(boardId).orElseThrow(BoardNotFound::new);
        if (!board.active) throw new BoardInactive();
        BoardPost post = new BoardPost(board, author, request.title(), request.editorType(), request.content());
        BoardPost result = postStore.saveNew(post);
        metrics.record("post_create", "success", "none");
        return result;
    }

    @Transactional
    BoardPost patch(String author, UUID postId, long expectedVersion, BoardPostPatch patch) {
        BoardPost post = postStore.findVisible(postId).orElseThrow(BoardPostNotFound::new);
        lockActiveBoard(post.board.id);
        requireAuthor(post, author);
        if (post.version != expectedVersion) throw new VersionConflict();
        if (patch.content() != null) validateContent("post_patch", post.editorType, patch.content());
        post.patch(patch);
        try {
            BoardPost result = postStore.saveChanged(post);
            metrics.record("post_patch", "success", "none");
            return result;
        } catch (OptimisticLockingFailureException exception) {
            metrics.record("post_patch", "rejected", "version_conflict");
            throw new VersionConflict();
        }
    }

    @Transactional
    void delete(String author, UUID postId, long expectedVersion) {
        BoardPost post = postStore.findVisible(postId).orElseThrow(BoardPostNotFound::new);
        lockActiveBoard(post.board.id);
        requireAuthor(post, author);
        if (post.version != expectedVersion) throw new VersionConflict();
        post.delete(author);
        try {
            postStore.saveChanged(post);
            metrics.record("post_delete", "success", "none");
        } catch (OptimisticLockingFailureException exception) {
            metrics.record("post_delete", "rejected", "version_conflict");
            throw new VersionConflict();
        }
    }

    private void lockActiveBoard(UUID boardId) {
        Board board = boardStore.lockForPostCreation(boardId).orElseThrow(BoardPostNotFound::new);
        if (!board.active) throw new BoardPostNotFound();
    }

    private void requireAuthor(BoardPost post, String author) {
        if (!post.isAuthor(author)) {
            metrics.record("post_change", "rejected", "not_author");
            throw new PostAuthorRequired();
        }
    }

    private void validateContent(String operation, EditorType editorType, String content) {
        try {
            contentPolicy.validate(editorType, content);
        } catch (ContentPolicyViolation exception) {
            metrics.record(operation, "rejected", "content_policy");
            throw exception;
        }
    }
}

class BoardPostNotFound extends RuntimeException {}
class BoardInactive extends RuntimeException {}
class PostAuthorRequired extends RuntimeException {}
