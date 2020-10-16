DROP TABLE IF EXISTS public.journal;

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

CREATE UNIQUE INDEX journal_ordering_idx ON public.journal (ordering);

DROP TABLE IF EXISTS public.snapshot;

CREATE TABLE IF NOT EXISTS public.snapshot
(
    persistence_id  VARCHAR(255) NOT NULL,
    sequence_number BIGINT       NOT NULL,
    created         BIGINT       NOT NULL,
    snapshot        BYTEA        NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);

DROP TABLE IF EXISTS public.projections_progress;

CREATE TABLE IF NOT EXISTS public.projections_progress
(
    projection_id VARCHAR(255) NOT NULL,
    akka_offset   json         NOT NULL,
    processed     BIGINT       NOT NULL,
    discarded     BIGINT       NOT NULL,
    failed        BIGINT       NOT NULL,
    PRIMARY KEY (projection_id)
);

DROP TABLE IF EXISTS public.projections_failures;

CREATE TABLE IF NOT EXISTS public.projections_failures
(
    ordering       BIGSERIAL,
    projection_id  VARCHAR(255) NOT NULL,
    akka_offset    json         NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    sequence_nr    BIGINT       NOT NULL,
    value          json         NOT NULL,
    error_type     VARCHAR(255) NOT NULL,
    error          text         NOT NULL
);

CREATE INDEX IF NOT EXISTS projections_projection_id_idx ON public.projections_failures (projection_id);
CREATE INDEX IF NOT EXISTS projections_projection_error_type_idx ON public.projections_failures (error_type);
CREATE INDEX IF NOT EXISTS projections_failures_ordering_idx ON public.projections_failures (ordering);
