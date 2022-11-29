CREATE MATERIALIZED VIEW IF NOT EXISTS delta.ordered_messages AS
  SELECT ser_manifest, ser_id, event
  FROM delta.messages
  WHERE timebucket is not null
  AND persistence_id is not null
  AND partition_nr is not null
  AND sequence_nr is not null
  AND timestamp is not null
  PRIMARY KEY(timebucket, timestamp, persistence_id, partition_nr, sequence_nr)
  WITH CLUSTERING ORDER BY (timestamp asc);