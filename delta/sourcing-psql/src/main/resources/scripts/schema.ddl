-- TODO: We may want to define a specific schema instead of using the public one

CREATE SEQUENCE IF NOT EXISTS public.event_offset;
CREATE SEQUENCE IF NOT EXISTS public.state_offset;

--
-- Event table for global events (ex: ACLs, permissions, orgs)
--
CREATE TABLE IF NOT EXISTS public.global_events(
    ordering bigint       NOT NULL DEFAULT nextval('event_offset'),
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
    ordering bigint      NOT NULL DEFAULT nextval('state_offset'),
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
    ordering bigint       NOT NULL DEFAULT nextval('event_offset'),
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
    ordering   bigint       NOT NULL DEFAULT nextval('state_offset'),
    type       text         NOT NULL,
    org        text         NOT NULL,
    project    text         NOT NULL,
    id         text         NOT NULL,
    tag        text         NOT NULL,
    rev        integer      NOT NULL,
    value      JSONB        NOT NULL,
    deprecated boolean      NOT NULL,
    instant    timestamptz  NOT NULL,
    PRIMARY KEY(type, org, project, tag, id),
    UNIQUE (org, project, tag, id)
);
CREATE INDEX IF NOT EXISTS scoped_states_ordering_idx ON public.scoped_states USING BRIN (ordering);
CREATE INDEX IF NOT EXISTS project_uuid_idx ON public.scoped_states((value->>'uuid')) WHERE type = 'project';

CREATE TABLE IF NOT EXISTS public.scoped_tombstones(
    ordering   bigint       NOT NULL DEFAULT nextval('state_offset'),
    type       text         NOT NULL,
    org        text         NOT NULL,
    project    text         NOT NULL,
    id         text         NOT NULL,
    tag        text         NOT NULL,
    diff       JSONB        NOT NULL,
    instant    timestamptz  NOT NULL,
    PRIMARY KEY(ordering)
);
CREATE INDEX IF NOT EXISTS scoped_tombstones_idx ON public.scoped_tombstones(org, project, tag, id);

--
-- Table for entity dependencies
--
CREATE TABLE IF NOT EXISTS public.entity_dependencies(
    org               text         NOT NULL,
    project           text         NOT NULL,
    id                text         NOT NULL,
    target_org        text         NOT NULL,
    target_project    text         NOT NULL,
    target_id         text         NOT NULL,
    PRIMARY KEY(org, project, id, target_org, target_project, target_id),
    CHECK (org != target_org or project != target_project or id != target_id)
);
CREATE INDEX IF NOT EXISTS entity_dependencies_reverse_idx ON public.entity_dependencies(target_org, target_project, target_id);

--
-- Table for projection offsets
--
CREATE TABLE IF NOT EXISTS public.projection_offsets(
    name         text,
    module       text,
    project      text,
    resource_id  text,
    ordering     bigint      NOT NULL,
    processed    bigint      NOT NULL,
    discarded    bigint      NOT NULL,
    failed       bigint      NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY(name)
);
CREATE INDEX IF NOT EXISTS projection_offsets_project_idx ON public.projection_offsets(project);
CREATE INDEX IF NOT EXISTS projection_offsets_resource_id_idx ON public.projection_offsets(resource_id);

--
-- Table for elem errors
--
CREATE TABLE IF NOT EXISTS public.failed_elem_logs(
    ordering            bigserial,
    projection_name     text        NOT NULL,
    projection_module   text        NOT NULL,
    projection_project  text,
    projection_id       text,
    entity_type         text        NOT NULL,
    elem_offset         bigint      NOT NULL,
    elem_id             text        NOT NULL,
    error_type          text        NOT NULL,
    message             text        NOT NULL,
    stack_trace         text        NOT NULL,
    instant             timestamptz DEFAULT NOW(),
    PRIMARY KEY(ordering)
);
CREATE INDEX IF NOT EXISTS failed_elem_logs_projection_name_idx ON public.failed_elem_logs(projection_name);
CREATE INDEX IF NOT EXISTS failed_elem_logs_projection_idx ON public.failed_elem_logs(projection_project, projection_id);
