CREATE TABLE IF NOT EXISTS public.blazegraph_slow_queries (
    ordering bigserial,
    project text,
    view_id text,
    instant timestamptz,
    duration integer,
    subject text,
    query text,
    PRIMARY KEY (ordering)
);