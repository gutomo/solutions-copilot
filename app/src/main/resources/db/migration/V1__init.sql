-- Phase 0 baseline: prove that pgvector loads and the vector type is usable.
-- Phase 1 replaces / populates this with the real ingestion pipeline.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS documents (
    id          BIGSERIAL PRIMARY KEY,
    source      TEXT        NOT NULL,
    chunk       TEXT        NOT NULL,
    embedding   vector(1024),                 -- Titan Text Embeddings v2 default
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HNSW index for cosine similarity search (used from Phase 1 onward).
CREATE INDEX IF NOT EXISTS idx_documents_embedding
    ON documents USING hnsw (embedding vector_cosine_ops);
