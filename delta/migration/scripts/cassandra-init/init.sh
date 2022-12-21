until cqlsh -f /cassandra-init/cassandra_1_5.ddl && cqlsh -f /cassandra-init/create-materialized-view.ddl && sstableloader --nodes localhost  /cassandra-backups/cassandra-0/delta_1_5/messages; do
  echo "cqlsh: Cassandra is unavailable to initialize - will retry later"
  sleep 2
done &
exec /docker-entrypoint.sh "$@"