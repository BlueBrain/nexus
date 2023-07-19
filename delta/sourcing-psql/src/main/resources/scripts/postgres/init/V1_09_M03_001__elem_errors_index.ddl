-----------------------------------------------------
-- Index on the instant column of failed_elem_logs --
-----------------------------------------------------
CREATE INDEX IF NOT EXISTS failed_elem_logs_instant_idx ON public.failed_elem_logs(instant);
