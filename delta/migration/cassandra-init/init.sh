until cqlsh -f /cassandra-init/cassandra.ddl && cqlsh -f /cassandra-init/create-materialized-view.ddl && cqlsh -f /cassandra-init/cassandra_1_5.ddl && sstableloader --nodes localhost  /cassandra-backups/cassandra-0/delta/messages && sstableloader --nodes localhost  /cassandra-backups/cassandra-1/delta/messages && sstableloader --nodes localhost  /cassandra-backups/cassandra-2/delta/messages ; do
  echo "cqlsh: Cassandra is unavailable to initialize - will retry later"
  sleep 2
done &
exec /docker-entrypoint.sh "$@"