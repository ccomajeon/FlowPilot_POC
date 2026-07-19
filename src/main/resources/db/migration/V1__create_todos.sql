CREATE TABLE todos (
    id uuid PRIMARY KEY,
    owner_id varchar(255) NOT NULL,
    title varchar(200) NOT NULL,
    description text,
    status varchar(20) NOT NULL DEFAULT 'TODO',
    due_date date,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_todos_owner CHECK (length(btrim(owner_id)) > 0),
    CONSTRAINT ck_todos_title CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_todos_description CHECK (description IS NULL OR length(description) <= 5000),
    CONSTRAINT ck_todos_status CHECK (status IN ('TODO','IN_PROGRESS','DONE'))
);
CREATE INDEX idx_todos_owner_created ON todos(owner_id, created_at DESC, id DESC);
CREATE INDEX idx_todos_owner_status_created ON todos(owner_id, status, created_at DESC, id DESC);
CREATE INDEX idx_todos_owner_due_date ON todos(owner_id, due_date);
