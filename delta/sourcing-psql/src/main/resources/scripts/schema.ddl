-- TODO: We may want to define a specific schema instead of using the public one

--
-- Event table for global events (ex: ACLs, permissions, orgs)
--
CREATE TABLE IF NOT EXISTS public.global_events(
    ordering BIGSERIAL,
    type     text         NOT NULL,
    id       text         NOT NULL,
    rev      integer      NOT NULL,
    value    JSONB        NOT NULL,
    instant  timestamptz  NOT NULL,
    PRIMARY KEY(type, id, rev)
);
CREATE INDEX IF NOT EXISTS global_events_ordering_idx ON public.global_events USING BRIN (ordering);

--
-- Table for global states (ex: ACLs, permissions, orgs)
--
CREATE TABLE IF NOT EXISTS public.global_states (
    ordering BIGSERIAL,
    type     text        NOT NULL,
    id       text        NOT NULL,
    rev      integer     NOT NULL,
    value    JSONB       NOT NULL,
    instant  timestamptz NOT NULL,
    PRIMARY KEY(type, id)
);
CREATE INDEX IF NOT EXISTS global_states_ordering_idx ON public.global_states USING BRIN (ordering);

--
-- Table for scoped events that belongs to a project
--
CREATE TABLE IF NOT EXISTS public.scoped_events(
    ordering BIGSERIAL,
    type     text         NOT NULL,
    org      text         NOT NULL,
    project  text         NOT NULL,
    id       text         NOT NULL,
    rev      integer      NOT NULL,
    value    JSONB        NOT NULL,
    instant  timestamptz  NOT NULL,
    PRIMARY KEY(type, org, project, id, rev),
    UNIQUE (org, project, id, rev)
);
CREATE INDEX IF NOT EXISTS scoped_events_ordering_idx ON public.scoped_events USING BRIN (ordering);

--
-- Table for scoped states that belongs to a project
--
CREATE TABLE IF NOT EXISTS public.scoped_states(
    ordering BIGSERIAL,
    type     text         NOT NULL,
    org      text         NOT NULL,
    project  text         NOT NULL,
    id       text         NOT NULL,
    tag      text         NOT NULL,
    rev      integer      NOT NULL,
    value    JSONB        NOT NULL,
    instant  timestamptz  NOT NULL,
    PRIMARY KEY(type, org, project, id, tag),
    UNIQUE (org, project, id, tag)
);
CREATE INDEX IF NOT EXISTS scoped_states_ordering_idx ON public.scoped_states USING BRIN (ordering);

--
-- Table for projection offsets
--
CREATE TABLE if NOT EXISTS public.projection_offsets(
    name   text,
    value JSONB NOT NULL,
    PRIMARY KEY(name)
);