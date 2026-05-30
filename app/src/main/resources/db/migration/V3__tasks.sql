-- Phase 2 slice 3: task-creation tool. Reuses the uuid-ossp extension already
-- enabled in V2 (vector_store). Priority/status defaults are belt-and-braces;
-- the Java service always normalizes and sends a value, so the column defaults
-- are mostly documentation. No CHECK constraints -- enforce in Java per the
-- design (lets us evolve the allowed set without a migration).

CREATE TABLE tasks (
    id          uuid        PRIMARY KEY DEFAULT uuid_generate_v4(),
    title       text        NOT NULL,
    description text,
    customer    text,
    due_date    date,
    priority    text        NOT NULL DEFAULT 'MEDIUM',
    status      text        NOT NULL DEFAULT 'OPEN',
    created_at  timestamptz NOT NULL DEFAULT now()
);
