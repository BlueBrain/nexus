CREATE TABLE IF NOT EXISTS public.scoped_events_new(
    ordering bigint       NOT NULL DEFAULT nextval('event_offset'),
    type     text         NOT NULL,
    org      text         NOT NULL,
    project  text         NOT NULL,
    id       text         NOT NULL,
    rev      integer      NOT NULL,
    value    JSONB        NOT NULL,
    instant  timestamptz  NOT NULL,
    PRIMARY KEY(org, project, id, rev)
);

INSERT INTO scoped_events_new select * from scoped_events;
ALTER TABLE scoped_events RENAME TO scoped_events_old;
ALTER TABLE scoped_events_new RENAME TO scoped_events;
DROP TABLE scoped_events_old;
CREATE INDEX IF NOT EXISTS scoped_events_type_idx ON public.scoped_events(type);
CREATE INDEX IF NOT EXISTS scoped_events_ordering_idx ON public.scoped_events (ordering);

CREATE TABLE IF NOT EXISTS public.scoped_states_new(
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
);

INSERT INTO scoped_states_new select * from scoped_states;
ALTER TABLE scoped_states RENAME TO scoped_states_old;
ALTER TABLE scoped_states_new RENAME TO scoped_states;
DROP TABLE scoped_states_old;
CREATE INDEX IF NOT EXISTS scoped_states_type_idx ON public.scoped_states(type);
CREATE INDEX IF NOT EXISTS scoped_states_ordering_idx ON public.scoped_states (ordering);
CREATE INDEX IF NOT EXISTS project_uuid_idx ON public.scoped_states((value->>'uuid')) WHERE type = 'project';