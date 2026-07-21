CREATE TABLE boards (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL,
    description varchar(1000),
    display_order integer NOT NULL,
    active boolean NOT NULL DEFAULT false,
    created_by varchar(255) NOT NULL,
    updated_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_boards_name UNIQUE (name),
    CONSTRAINT ck_boards_name CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT ck_boards_description CHECK (description IS NULL OR length(description) <= 1000),
    CONSTRAINT ck_boards_display_order CHECK (display_order BETWEEN 0 AND 1000000),
    CONSTRAINT ck_boards_created_by CHECK (length(btrim(created_by)) BETWEEN 1 AND 255),
    CONSTRAINT ck_boards_updated_by CHECK (length(btrim(updated_by)) BETWEEN 1 AND 255)
);

CREATE INDEX idx_boards_active_order ON boards(active, display_order, id);

CREATE TABLE board_posts (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL,
    author_id varchar(255) NOT NULL,
    title varchar(200) NOT NULL,
    editor_type varchar(20) NOT NULL,
    content text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    deleted_by varchar(255),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_board_posts_board FOREIGN KEY (board_id) REFERENCES boards(id) ON DELETE RESTRICT,
    CONSTRAINT ck_board_posts_author CHECK (length(btrim(author_id)) BETWEEN 1 AND 255),
    CONSTRAINT ck_board_posts_title CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_board_posts_editor_type CHECK (editor_type IN ('MARKDOWN', 'RICH_TEXT')),
    CONSTRAINT ck_board_posts_content CHECK (length(content) BETWEEN 1 AND 100000 AND length(btrim(content)) > 0),
    CONSTRAINT ck_board_posts_deleted CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL
            AND length(btrim(deleted_by)) BETWEEN 1 AND 255)
    )
);

CREATE INDEX idx_board_posts_visible_created
    ON board_posts(board_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
