-- Transactional idempotency results. The incomplete row exists only inside the
-- same transaction as the business write and is never committed by the service.
CREATE TABLE idempotency_record (
    user_id bigint NOT NULL,
    operation_hash char(64) NOT NULL,
    key_hash char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    status integer,
    content_type text,
    response_body bytea,
    expires_at timestamptz,
    PRIMARY KEY (user_id, operation_hash, key_hash),
    CONSTRAINT idempotency_record_response_check CHECK (
        (status IS NULL AND content_type IS NULL AND response_body IS NULL AND expires_at IS NULL)
        OR (status IS NOT NULL AND status BETWEEN 100 AND 599 AND content_type IS NOT NULL AND response_body IS NOT NULL AND expires_at IS NOT NULL)
    )
);
