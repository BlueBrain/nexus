CREATE TABLE IF NOT EXISTS public.migration_offset(
    name         text        NOT NULL,
    akka_offset  text        NOT NULL,
    processed    bigint      NOT NULL,
    discarded    bigint      NOT NULL,
    failed       bigint      NOT NULL,
    instant      timestamptz NOT NULL,
    PRIMARY KEY(name)
);

CREATE TABLE IF NOT EXISTS public.ignored_events(
    type            text        NOT NULL,
    persistence_id  text        NOT NULL,
    sequence_nr     bigint      NOT NULL,
    payload         JSONB       NOT NULL,
    instant         timestamptz NOT NULL,
    akka_offset     text        NOT NULL
);