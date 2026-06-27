-- V2__memory.sql

CREATE TABLE memory_docs (
  path        TEXT PRIMARY KEY,
  content     TEXT NOT NULL,
  scope       TEXT NOT NULL DEFAULT 'Default',
  ts          INTEGER NOT NULL,
  byte_size   INTEGER NOT NULL,
  hash_sha256 TEXT NOT NULL
);

CREATE TABLE memory_chunks (
  doc_path     TEXT NOT NULL REFERENCES memory_docs(path) ON DELETE CASCADE,
  chunk_idx    INTEGER NOT NULL,
  content      TEXT NOT NULL,
  token_count  INTEGER NOT NULL,
  embedding    BLOB,
  ts           INTEGER NOT NULL,
  PRIMARY KEY (doc_path, chunk_idx)
);

CREATE VIRTUAL TABLE memory_chunks_fts USING fts5(
  doc_path UNINDEXED,
  chunk_idx UNINDEXED,
  content,
  content='memory_chunks',
  content_rowid='rowid',
  tokenize='porter unicode61'
);

CREATE TRIGGER memory_chunks_ai AFTER INSERT ON memory_chunks BEGIN
  INSERT INTO memory_chunks_fts(rowid, doc_path, chunk_idx, content)
  VALUES (new.rowid, new.doc_path, new.chunk_idx, new.content);
END;

CREATE TRIGGER memory_chunks_ad AFTER DELETE ON memory_chunks BEGIN
  INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, doc_path, chunk_idx, content)
  VALUES('delete', old.rowid, old.doc_path, old.chunk_idx, old.content);
END;

CREATE TRIGGER memory_chunks_au AFTER UPDATE ON memory_chunks BEGIN
  INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, doc_path, chunk_idx, content)
  VALUES('delete', old.rowid, old.doc_path, old.chunk_idx, old.content);
  INSERT INTO memory_chunks_fts(rowid, doc_path, chunk_idx, content)
  VALUES (new.rowid, new.doc_path, new.chunk_idx, new.content);
END;

CREATE VIRTUAL TABLE memory_chunks_vec USING vec0(
  doc_path TEXT,
  chunk_idx INTEGER,
  embedding FLOAT[1536]
);