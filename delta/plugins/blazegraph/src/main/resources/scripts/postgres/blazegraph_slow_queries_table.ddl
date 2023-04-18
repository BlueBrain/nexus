CREATE TABLE IF NOT EXISTS public.blazegraph_slow_queries (
    ordering bigserial,
    project text NOT NULL,
    view_id text NOT NULL,
    instant timestamptz NOT NULL,
    duration integer NOT NULL,
    subject JSONB NOT NULL,
    query text NOT NULL,
    PRIMARY KEY (ordering)
);
