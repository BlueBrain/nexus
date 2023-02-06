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

CREATE TABLE IF NOT EXISTS public.migration_resources_diff_offset(
    project   text         NOT NULL,
    ordering  bigint,
    PRIMARY KEY(project)
);

CREATE TABLE IF NOT EXISTS public.migration_resources_diff(
    type      text         NOT NULL,
    project   text         NOT NULL,
    id        text         NOT NULL,
    value_1_7 JSONB,
    value_1_8 JSONB,
    error     text,
    PRIMARY KEY(type, project, id)
);

CREATE TABLE IF NOT EXISTS public.migration_project_count(
    project   text         NOT NULL,
    event_count_1_7 bigint,
    event_count_1_8 bigint,
    resource_count_1_7 bigint,
    resource_count_1_8 bigint,
    error     text,
    PRIMARY KEY(project)
);

CREATE TABLE IF NOT EXISTS public.migration_blazegraph_count(
    project   text         NOT NULL,
    id        text         NOT NULL,
    count_1_7 bigint,
    count_1_8 bigint,
    PRIMARY KEY(project, id)
);

CREATE TABLE IF NOT EXISTS public.migration_elasticsearch_count(
    project   text         NOT NULL,
    id        text         NOT NULL,
    count_1_7 bigint,
    count_1_8 bigint,
    PRIMARY KEY(project, id)
);

CREATE TABLE IF NOT EXISTS public.migration_composite_count(
    project       text         NOT NULL,
    id            text         NOT NULL,
    space_id text         NOT NULL,
    count_1_7 bigint,
    count_1_8 bigint,
    PRIMARY KEY(project, id, space_id)
);