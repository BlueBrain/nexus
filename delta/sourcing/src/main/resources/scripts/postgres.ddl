CREATE TABLE IF NOT EXISTS public.event_journal(
  ordering BIGSERIAL,
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  deleted BOOLEAN DEFAULT FALSE NOT NULL,

  writer VARCHAR(255) NOT NULL,
  write_timestamp BIGINT,
  adapter_manifest VARCHAR(255),

  event_ser_id INTEGER NOT NULL,
  event_ser_manifest VARCHAR(255) NOT NULL,
  event_payload BYTEA NOT NULL,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY(persistence_id, sequence_number)
);

CREATE UNIQUE INDEX event_journal_ordering_idx ON public.event_journal(ordering);

CREATE TABLE IF NOT EXISTS public.event_tag(
    event_id BIGINT,
    tag VARCHAR(256),
    PRIMARY KEY(event_id, tag),
    CONSTRAINT fk_event_journal
      FOREIGN KEY(event_id)
      REFERENCES event_journal(ordering)
      ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.snapshot (
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  created BIGINT NOT NULL,

  snapshot_ser_id INTEGER NOT NULL,
  snapshot_ser_manifest VARCHAR(255) NOT NULL,
  snapshot_payload BYTEA NOT NULL,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY(persistence_id, sequence_number)
);


CREATE TABLE IF NOT EXISTS public.projections_progress
(
    projection_id VARCHAR(255) NOT NULL,
    akka_offset         BIGINT,
    timestamp           BIGINT       NOT NULL,
    processed           BIGINT       NOT NULL,
    discarded           BIGINT       NOT NULL,
    warnings            BIGINT       NOT NULL,
    failed              BIGINT       NOT NULL,
    value               json         NOT NULL,
    value_timestamp     BIGINT       NOT NULL,
    PRIMARY KEY (projection_id)
);

CREATE TABLE IF NOT EXISTS public.projections_errors
(
    ordering            BIGSERIAL,
    projection_id       VARCHAR(255) NOT NULL,
    akka_offset         BIGINT,
    timestamp           BIGINT       NOT NULL,
    persistence_id      VARCHAR(255) NOT NULL,
    sequence_nr         BIGINT       NOT NULL,
    value               json,
    value_timestamp     BIGINT,
    severity            VARCHAR(255) NOT NULL,
    error_type          VARCHAR(255),
    message             text         NOT NULL
);

CREATE INDEX IF NOT EXISTS projections_projection_id_idx ON public.projections_errors (projection_id);
CREATE INDEX IF NOT EXISTS projections_projection_error_type_idx ON public.projections_errors (error_type);
CREATE INDEX IF NOT EXISTS projections_failures_ordering_idx ON public.projections_errors (ordering);
