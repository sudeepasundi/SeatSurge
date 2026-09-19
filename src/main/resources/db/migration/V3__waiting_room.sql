-- Virtual waiting room for high-demand drops: when enabled, fans must queue and present an admission
-- token to hold seats. Admission is paced at admission_rate_per_minute from saleStartsAt.
ALTER TABLE events ADD COLUMN waiting_room_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN admission_rate_per_minute INT NOT NULL DEFAULT 600 CHECK (admission_rate_per_minute > 0);
