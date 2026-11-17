ALTER TABLE outbox_events
    ADD COLUMN claim_token uuid;
ALTER TABLE outbox_events
    ADD COLUMN claimed_until timestamptz;
ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_claim_pair
        CHECK ((claim_token IS NULL) = (claimed_until IS NULL));
