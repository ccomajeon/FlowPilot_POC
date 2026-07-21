package com.flowpilot.todo;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BoardPostStore {
    Optional<BoardPost> findVisible(UUID id);
    Page<BoardPost> visiblePosts(UUID boardId, Pageable pageable);
    BoardPost saveNew(BoardPost post);
    BoardPost saveChanged(BoardPost post);
}

interface BoardPostRepository extends JpaRepository<BoardPost, UUID>, BoardPostStore {
    @Override
    @Query("select p from BoardPost p join fetch p.board b "
        + "where p.id = :id and p.deletedAt is null and b.active = true")
    Optional<BoardPost> findVisible(@Param("id") UUID id);

    @Override
    @Query("select p from BoardPost p join p.board b "
        + "where b.id = :boardId and b.active = true and p.deletedAt is null")
    Page<BoardPost> visiblePosts(@Param("boardId") UUID boardId, Pageable pageable);

    @Override
    default BoardPost saveNew(BoardPost post) {
        return saveAndFlush(post);
    }

    @Override
    default BoardPost saveChanged(BoardPost post) {
        return saveAndFlush(post);
    }
}
