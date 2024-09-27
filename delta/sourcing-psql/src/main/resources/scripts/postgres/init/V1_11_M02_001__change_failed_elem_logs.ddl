-- Stacktrace and message are now deprecated so they can be nullable
ALTER TABLE failed_elem_logs ALTER COLUMN message DROP NOT NULL;
ALTER TABLE failed_elem_logs ALTER COLUMN stack_trace DROP NOT NULL;

-- Adding a new column
ALTER TABLE failed_elem_logs ADD COLUMN IF NOT EXISTS reason JSONB;

