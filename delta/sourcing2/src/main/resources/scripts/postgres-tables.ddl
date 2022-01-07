CREATE TABLE IF NOT EXISTS public.events(
  ordering        BIGSERIAL,
  entity_type     text       NOT NULL,
  entity_id       text       NOT NULL,
  revision        integer    NOT NULL,
  scope           text       NOT NULL,
  payload         JSONB      NOT NULL,
  tracks          integer[],
  instant         timestampz NOT NULL,
  written_at      timestampz NOT NULL,
  write_version   text       NOT NULL,

  PRIMARY KEY(entity_type, entity_id, revision),
  UNIQUE (entity_id, revision, scope)
);

CREATE EXTENSION IF NOT EXISTS intarray WITH SCHEMA public;
CREATE INDEX events_tags_idx ON public.journal USING GIN (tags public.gin__int_ops);
CREATE INDEX events_ordering_idx ON public.journal USING BRIN (ordering);

CREATE TABLE IF NOT EXISTS public.tracks(
    id   SERIAL,
    name TEXT      NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS tracks_name_idx on public.tracks (name);

CREATE TABLE IF NOT EXISTS public.states (
    ordering             BIGSERIAL,
    entity_type          text       NOT NULL,
    entity_id            text       NOT NULL,
    revision             integer    NOT NULL,
    payload              JSONB      NOT NULL,
    tracks               integer[],
    tag                  text,
    updated_at           timestampz NOT NULL,
    written_at           timestampz NOT NULL,
    write_version        text       NOT NULL

  PRIMARY KEY(entity_type, entity_id, tag)
);

CREATE TABLE IF NOT EXISTS public.projection_progresses
(
    projection_id VARCHAR(255) NOT NULL,
    current_offset      BIGINT,
    timestamp           BIGINT       NOT NULL,
    processed           BIGINT       NOT NULL,
    discarded           BIGINT       NOT NULL,
    failed              BIGINT       NOT NULL,
    value               JSONB        NOT NULL,
    value_timestamp     timestampz   NOT NULL,
    PRIMARY KEY (projection_id)
);

CREATE TABLE IF NOT EXISTS public.projection_errors
(
    ordering            BIGSERIAL,
    projection_id       text          NOT NULL,
    current_offset      BIGINT,
    timestamp           timestampz    NOT NULL,
    entity_type         text NOT NULL,
    entity_id           text NOT NULL,
    revision            BIGINT        NOT NULL,
    value               JSONB,
    value_timestamp     timestampz    NOT NULL,
    error_type          text          NOT NULL,
    message             text          NOT NULL
);

CREATE INDEX IF NOT EXISTS projections_projection_id_idx ON public.projections_errors (projection_id);
CREATE INDEX IF NOT EXISTS projections_projection_error_type_idx ON public.projections_errors (error_type);
CREATE INDEX IF NOT EXISTS projections_failures_ordering_idx ON public.projections_errors (ordering);
