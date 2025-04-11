--
-- Table to keep track of resource deletion
--
CREATE TABLE IF NOT EXISTS public.scoped_event_tombstones(
    -- Primary key based on a sequence 'event_offset' shared so that events and resource tombstones
    -- can be queried in the chronological order
    ordering   bigint       NOT NULL DEFAULT nextval('event_offset'),
    -- Identifiers of the resource
    type       text         NOT NULL,
    org        text         NOT NULL,
    project    text         NOT NULL,
    id         text         NOT NULL,
    -- value of the tombstone
    value      JSONB        NOT NULL,
    -- Instant the tombstone was created
    instant    timestamptz  DEFAULT NOW(),
    PRIMARY KEY(ordering)
);
CREATE INDEX IF NOT EXISTS scoped_event_tombstones_idx ON public.scoped_event_tombstones(org, project, id);

