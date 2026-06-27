-- Kleio/DocWH P1 Stage 1.2 — the full-text plane (contracts §3). Maintains
-- `parts.content_tsv` via a trigger and the GIN index keyword `query` rides.
--
-- Text-search config is FIXED here (in the migration), not per-instance. We use
-- 'simple' (no language stemming) deliberately: the corpus is multilingual
-- (cs + en, architecture §11) and 'simple' avoids English-only stemming that
-- would degrade Czech recall. Cross-lingual depth is the vector plane's job (P2);
-- the keyword plane stays language-neutral.

CREATE OR REPLACE FUNCTION parts_tsv_update() RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', coalesce(NEW.content_text, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER parts_tsv_trigger
    BEFORE INSERT OR UPDATE ON parts
    FOR EACH ROW EXECUTE FUNCTION parts_tsv_update();

CREATE INDEX idx_parts_content_tsv ON parts USING GIN (content_tsv);
