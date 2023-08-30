-----------------------------------------------------
-- Index on the instant column of failed_elem_logs --
-----------------------------------------------------
CREATE INDEX IF NOT EXISTS state_value_types_idx ON public.scoped_states USING GIN ((value->'types'));