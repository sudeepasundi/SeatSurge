-- Token that owns the Redis seat locks of a hold (compare-and-delete on release).
ALTER TABLE holds ADD COLUMN lock_token VARCHAR(64);

-- A fan may have at most one ACTIVE hold per event. Enforced by the database so that parallel
-- requests from the same user cannot slip past the per-user ticket limit.
CREATE UNIQUE INDEX uq_holds_one_active_per_user_event ON holds (user_id, event_id) WHERE status = 'ACTIVE';
