-- Phase 1 RAG: table that Spring AI's PgVectorStore reads and writes.
-- The V1 `documents` table was an illustrative placeholder; the real ingestion
-- pipeline targets `vector_store`. `documents` is left in place for now and can
-- be dropped in a later migration once nothing references it.
--
-- Schema matches Spring AI 1.0.x PgVectorStore expectations so that
-- spring.ai.vectorstore.pgvector.initialize-schema=false is safe.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS vector_store (
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1024)               -- Amazon Titan Text Embeddings v2
);

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);
