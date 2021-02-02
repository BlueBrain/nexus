  until cqlsh -f /cassandra-init/cassandra.ddl && cqlsh -f /cassandra-init/cassandra_1_5.ddl && sstableloader --nodes localhost  /cassandra-backups/cassandra-0/delta/tag_views && sstableloader --nodes localhost  /cassandra-backups/cassandra-1/delta/tag_views && sstableloader --nodes localhost  /cassandra-backups/cassandra-2/delta/tag_views ; do
  echo "cqlsh: Cassandra is unavailable to initialize - will retry later"
  sleep 2
done &
exec /docker-entrypoint.sh "$@"