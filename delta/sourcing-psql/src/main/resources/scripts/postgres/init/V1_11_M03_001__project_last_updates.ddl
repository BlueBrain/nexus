CREATE TABLE IF NOT EXISTS public.project_last_updates(
    org        text            NOT NULL,
    project    text            NOT NULL,
    last_instant timestamptz   NOT NULL,
    last_ordering bigint NOT NULL,
    PRIMARY KEY(org, project)
);

CREATE INDEX IF NOT EXISTS project_last_updates_last_ordering_idx ON public.project_last_updates(last_ordering);

