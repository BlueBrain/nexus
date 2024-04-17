CREATE TABLE IF NOT EXISTS public.ship_runs(
    ordering     bigserial,
    started_at   timestamptz  NOT NULL,
    ended_at     timestamptz  NOT NULL,
    command      JSONB        NOT NULL,
    success      boolean      NOT NULL,
    error        text,
    report       JSONB
)

