CREATE TABLE IF NOT EXISTS public.partition_config(
    id         bool         DEFAULT true,
    strategy   JSONB        NOT NULL,
    instant    timestamptz  DEFAULT NOW(),
    PRIMARY KEY(id),
    CONSTRAINT singleton_check CHECK (id)
);

