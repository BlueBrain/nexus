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
CREATE INDEX IF NOT EXISTS org_uuid_idx ON public.global_states((value->>'uuid')) WHERE type = 'organization';

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
    PRIMARY KEY(type, org, project, tag, id),
    UNIQUE (org, project, tag, id)
);
CREATE INDEX IF NOT EXISTS scoped_states_ordering_idx ON public.scoped_states USING BRIN (ordering);
CREATE INDEX IF NOT EXISTS project_uuid_idx ON public.scoped_states((value->>'uuid')) WHERE type = 'project';

--
-- Table for projection offsets
--
CREATE TABLE if NOT EXISTS public.projection_offsets(
    name         text,
    project      text,
    resource_id  text,
    value        JSONB       NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY(name)
);
CREATE INDEX IF NOT EXISTS projection_offsets_project_idx on public.projection_offsets(project);
CREATE INDEX IF NOT EXISTS projection_offsets_resource_id_idx on public.projection_offsets(resource_id);