CREATE TABLE IF NOT EXISTS public.ship_dropped_events(
    ordering bigint       NOT NULL,
    type     text         NOT NULL,
    org      text         NOT NULL,
    project  text         NOT NULL,
    id       text         NOT NULL,
    rev      integer      NOT NULL,
    value    JSONB        NOT NULL,
    instant  timestamptz  NOT NULL,
    PRIMARY KEY(org, project, id, rev)
);

