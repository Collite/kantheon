-- V6__memory_categories.sql

ALTER TABLE memory_docs ADD COLUMN category TEXT NOT NULL DEFAULT 'Document';
CREATE INDEX idx_memory_docs_category ON memory_docs(category);