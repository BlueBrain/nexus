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
CREATE INDEX IF NOT EXISTS global_events_ordering_idx ON public.global_events (ordering);

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
CREATE INDEX IF NOT EXISTS global_states_ordering_idx ON public.global_states (ordering);
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
    PRIMARY KEY(org, project, id, rev)
) PARTITION BY HASH (org, project);

CREATE INDEX IF NOT EXISTS scoped_events_type_idx ON public.scoped_events(type);
CREATE INDEX IF NOT EXISTS scoped_events_ordering_idx ON public.scoped_events (ordering);

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
    PRIMARY KEY(org, project, tag, id)
) PARTITION BY HASH (org, project);

CREATE INDEX IF NOT EXISTS scoped_states_type_idx ON public.scoped_states(type);
CREATE INDEX IF NOT EXISTS scoped_states_ordering_idx ON public.scoped_states (ordering);
CREATE INDEX IF NOT EXISTS project_uuid_idx ON public.scoped_states((value->>'uuid')) WHERE type = 'project';
CREATE INDEX IF NOT EXISTS state_value_types_idx ON public.scoped_states USING GIN ((value->'types'));

--
-- Table for tombstones for scoped states
-- These tombstones are meant to inform streaming operations that a resource
-- has been deleted or doesn't have a certain type anymore so that the client
-- can take the appropriate action
-- The tombstones are deleted by Delta after a configured period
--
CREATE TABLE IF NOT EXISTS public.scoped_tombstones(
    -- Primary key based on a sequence 'state_offset' shared so that states and tombstones
    -- can be queried in the chronological order
    ordering   bigint       NOT NULL DEFAULT nextval('state_offset'),
    -- Identifiers of the resource
    type       text         NOT NULL,
    org        text         NOT NULL,
    project    text         NOT NULL,
    id         text         NOT NULL,
    -- Tag of the state the tombstone is associated to
    tag        text         NOT NULL,
    -- Cause of the tombstone
    cause     JSONB        NOT NULL,
    -- Instant the tombstone was created
    instant    timestamptz  NOT NULL,
    PRIMARY KEY(ordering)
);
CREATE INDEX IF NOT EXISTS scoped_tombstones_idx ON public.scoped_tombstones(org, project, tag, id);
CREATE INDEX IF NOT EXISTS scoped_tombstones_deleted_idx ON public.scoped_tombstones((cause->>'deleted'));
CREATE INDEX IF NOT EXISTS tombstones_scope_types_idx ON public.scoped_tombstones USING GIN ((cause->'types'));

--
-- Table for ephemeral scoped states that belongs to a project
--
CREATE TABLE IF NOT EXISTS public.ephemeral_states(
    type       text         NOT NULL,
    org        text         NOT NULL,
    project    text         NOT NULL,
    id         text         NOT NULL,
    value      JSONB        NOT NULL,
    instant    timestamptz  NOT NULL,
    expires    timestamptz  NOT NULL,
    PRIMARY KEY(org, project, id)
);
CREATE INDEX IF NOT EXISTS ephemeral_states_type_idx ON public.ephemeral_states(type);

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
-- Table for composite offsets
--
CREATE TABLE IF NOT EXISTS public.composite_offsets(
    project      text        NOT NULL,
    view_id      text        NOT NULL,
    rev          integer     NOT NULL,
    source_id    text        NOT NULL,
    target_id    text        NOT NULL,
    run          text        NOT NULL,
    ordering     bigint      NOT NULL,
    processed    bigint      NOT NULL,
    discarded    bigint      NOT NULL,
    failed       bigint      NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY(project, view_id, rev, source_id, target_id, run)
);

--
-- Table for projection restarts
--
CREATE TABLE IF NOT EXISTS public.projection_restarts(
    ordering     bigserial,
    name         text         NOT NULL,
    value        JSONB        NOT NULL,
    instant      timestamptz  NOT NULL,
    acknowledged boolean      NOT NULL,
    PRIMARY KEY(ordering)
);

--
-- Table for composite views restarts
--
CREATE TABLE IF NOT EXISTS public.composite_restarts(
    ordering     bigserial,
    project      text,
    id           text         NOT NULL,
    value        JSONB        NOT NULL,
    instant      timestamptz  NOT NULL,
    acknowledged boolean      NOT NULL,
    PRIMARY KEY(ordering)
);

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
    elem_project        text,
    rev                 integer     NOT NULL,
    error_type          text        NOT NULL,
    message             text,
    stack_trace         text,
    instant             timestamptz DEFAULT NOW(),
    reason              JSONB,
    PRIMARY KEY(ordering)
);
CREATE INDEX IF NOT EXISTS failed_elem_logs_projection_name_idx ON public.failed_elem_logs(projection_name);
CREATE INDEX IF NOT EXISTS failed_elem_logs_projection_idx ON public.failed_elem_logs(projection_project, projection_id);
CREATE INDEX IF NOT EXISTS failed_elem_logs_instant_idx ON public.failed_elem_logs(instant);

--
-- Table for project deletion result
--
CREATE TABLE IF NOT EXISTS public.deleted_project_reports(
    ordering   bigserial,
    value      JSONB       NOT NULL,
    PRIMARY KEY(ordering)
);

CREATE TABLE IF NOT EXISTS public.blazegraph_queries (
    ordering bigserial,
    project  text        NOT NULL,
    view_id  text        NOT NULL,
    instant  timestamptz NOT NULL,
    duration integer     NOT NULL,
    subject  JSONB       NOT NULL,
    query    text        NOT NULL,
    failed   boolean     NOT NULL,
    PRIMARY KEY (ordering)
);

--
-- Table for the errors that occurred during scope initialization
--
CREATE TABLE IF NOT EXISTS public.scope_initialization_errors(
    ordering bigserial,
    type     text         NOT NULL,
    org      text         NOT NULL,
    project  text         NOT NULL,
    message  text         NOT NULL,
    instant  timestamptz  DEFAULT NOW(),
    PRIMARY KEY(ordering)
);

CREATE TABLE IF NOT EXISTS public.project_last_updates(
    org        text            NOT NULL,
    project    text            NOT NULL,
    last_instant timestamptz   NOT NULL,
    last_ordering bigint NOT NULL,
    PRIMARY KEY(org, project)
);

CREATE INDEX IF NOT EXISTS project_last_updates_last_ordering_idx ON public.project_last_updates(last_ordering);

