----------------------------------------------------
-- Index on the types column of scoped_tombstones --
----------------------------------------------------
CREATE INDEX IF NOT EXISTS tombstones_scope_types_idx ON public.scoped_tombstones USING GIN ((cause->'types'));