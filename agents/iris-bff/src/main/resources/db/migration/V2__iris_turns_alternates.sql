-- Phase 3 Stage 3.1 (T5): persist the agents Themis offered as RoutingPickChips
-- on a needs_user_pick turn, so a subsequent chip-click reissue (routing_hint)
-- can be validated and the routing UX is auditable. Empty for ordinary turns.
ALTER TABLE iris_turns
    ADD COLUMN alternates_offered TEXT[] NOT NULL DEFAULT '{}';
