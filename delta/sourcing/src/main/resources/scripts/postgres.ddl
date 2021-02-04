CREATE TABLE IF NOT EXISTS public.journal
(
    ordering        BIGSERIAL,
    persistence_id  VARCHAR(255)               NOT NULL,
    sequence_number BIGINT                     NOT NULL,
    deleted         BOOLEAN      DEFAULT FALSE NOT NULL,
    tags            VARCHAR(255) DEFAULT NULL,
    message         BYTEA                      NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS journal_ordering_idx ON public.journal (ordering);

CREATE TABLE IF NOT EXISTS public.snapshot
(
    persistence_id  VARCHAR(255) NOT NULL,
    sequence_number BIGINT       NOT NULL,
    created         BIGINT       NOT NULL,
    snapshot        BYTEA        NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);


CREATE TABLE IF NOT EXISTS public.projections_progress
(
    projection_id VARCHAR(255) NOT NULL,
    akka_offset   BIGINT,
    timestamp     BIGINT       NOT NULL,
    processed     BIGINT       NOT NULL,
    discarded     BIGINT       NOT NULL,
    warnings      BIGINT       NOT NULL,
    failed        BIGINT       NOT NULL,
    value         json         NOT NULL,
    PRIMARY KEY (projection_id)
);

CREATE TABLE IF NOT EXISTS public.projections_errors
(
    ordering       BIGSERIAL,
    projection_id  VARCHAR(255) NOT NULL,
    akka_offset    BIGINT,
    timestamp      BIGINT       NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    sequence_nr    BIGINT       NOT NULL,
    value          json,
    severity       VARCHAR(255) NOT NULL,
    error_type     VARCHAR(255),
    message        text         NOT NULL
);

CREATE INDEX IF NOT EXISTS projections_projection_id_idx ON public.projections_errors (projection_id);
CREATE INDEX IF NOT EXISTS projections_projection_error_type_idx ON public.projections_errors (error_type);
CREATE INDEX IF NOT EXISTS projections_failures_ordering_idx ON public.projections_errors (ordering);
