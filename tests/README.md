# Nexus integration tests

Starts the delta ecosystem with docker-compose and run tests on it

First, run:
```shell
docker-compose -f docker/docker-compose-postgres.yml up -d # or docker/docker-compose-cassandra.yml
```

To run the all the tests:
```sbtshell
test
```

To run just some tests:
```sbtshell
testOnly *RemoteStorageSpec
```

## Debugging

Start dependencies as containers locally:

```
docker run --name cassandra \
  -e JVM_OPTS="-Xms2g -Xmx2g -Dcassandra.skip_wait_for_gossip_to_settle=0" \
  -e MAX_HEAP_SIZE="2G" \
  -e HEAP_NEWSIZE="100m" \
  -p 9042:9042 \
  -d cassandra:3.11.10

docker run --name keycloak \
  -e KEYCLOAK_USER="admin" \
  -e KEYCLOAK_PASSWORD="admin" \
  -e KEYCLOAK_FRONTEND_URL="http://localhost:8081/auth" \
  -p 8081:8080 \
  -d jboss/keycloak:11.0.1

docker run --name elasticsearch \
  -e ES_JAVA_OPTS="-Xmx2G" \
  -e discovery.type="single-node" \
  -e bootstrap.memory_lock="true" \
  -p 9200:9200 \
  -d docker.elastic.co/elasticsearch/elasticsearch:7.16.2

docker run --name blazegraph \
  -e JAVA_OPTS="-Djava.awt.headless=true -XX:MaxDirectMemorySize=100m -Xms1g -Xmx1g" \
  -p 9999:9999 \
  -d bluebrain/blazegraph-nexus:2.1.5

```

Package the service:

```
sbt app/Universal/stage
```

Wipe the ddata cache:
```
rm -rf /tmp/delta-cache1
rm -rf /tmp/delta-cache2
rm -rf /tmp/delta-cache3
```

Run the service in debug mode:

```
DELTA_PLUGINS=delta/app/target/universal/stage/plugins \
delta/app/target/universal/stage/bin/delta-app \
  -jvm-debug 5005 \
  -Dapp.database.cassandra.contact-points.1="localhost:9042" \
  -Dapp.database.cassandra.keyspace-autocreate=true \
  -Dapp.database.cassandra.tables-autocreate=true \
  -Dplugins.blazegraph.base="http://localhost:9999/blazegraph" \
  -Dplugins.elasticsearch.base="http://localhost:9200" \
  -Dapp.cluster.seeds=127.0.0.1:25520 \
  -Dapp.cluster.remote-interface=127.0.0.1 \
  -Dapp.http.interface="127.0.0.1" \
  -Dapp.http.base-uri=http://127.0.0.1:8080/v1 \
  -Dakka.persistence.cassandra.events-by-tag.first-time-bucket=20210401T00:00 \
  -Dakka.persistence.cassandra.events-by-tag.eventual-consistency-delay=4s \
  -Dakka.persistence.cassandra.query.refresh-interval=1s \
  -Dakka.cluster.distributed-data.durable.lmdb.dir=/tmp/delta-cache1 \
  -Dkamon.prometheus.embedded-server.hostname="127.0.0.1" \
  -Dkamon.status-page.listen.hostname="127.0.0.1"
```

Attach the IDE to the debug port 5005 (Remote Application) and start the debug process.

For starting a cluster bring up new loopback interfaces:

```
sudo ifconfig lo0 alias 127.0.0.2 up
sudo ifconfig lo0 alias 127.0.0.3 up
```

... and start additional replicas:

```
DELTA_PLUGINS=delta/app/target/universal/stage/plugins \
delta/app/target/universal/stage/bin/delta-app \
  -jvm-debug 50052 \
  -Dapp.database.cassandra.contact-points.1="localhost:9042" \
  -Dplugins.blazegraph.base="http://localhost:9999/blazegraph" \
  -Dplugins.elasticsearch.base="http://localhost:9200" \
  -Dapp.cluster.seeds=127.0.0.1:25520 \
  -Dapp.cluster.remote-interface=127.0.0.2 \
  -Dapp.http.interface="127.0.0.2" \
  -Dapp.http.base-uri=http://127.0.0.1:8080/v1 \
  -Dakka.persistence.cassandra.events-by-tag.first-time-bucket=20210401T00:00 \
  -Dakka.persistence.cassandra.events-by-tag.eventual-consistency-delay=4s \
  -Dakka.persistence.cassandra.query.refresh-interval=1s \
  -Dakka.cluster.distributed-data.durable.lmdb.dir=/tmp/delta-cache2 \
  -Dkamon.prometheus.embedded-server.hostname="127.0.0.2" \
  -Dkamon.status-page.listen.hostname="127.0.0.2"

DELTA_PLUGINS=delta/app/target/universal/stage/plugins \
delta/app/target/universal/stage/bin/delta-app \
  -jvm-debug 50053 \
  -Dapp.database.cassandra.contact-points.1="localhost:9042" \
  -Dplugins.blazegraph.base="http://localhost:9999/blazegraph" \
  -Dplugins.elasticsearch.base="http://localhost:9200" \
  -Dapp.cluster.seeds=127.0.0.1:25520 \
  -Dapp.cluster.remote-interface=127.0.0.3 \
  -Dapp.http.interface="127.0.0.3" \
  -Dapp.http.base-uri=http://127.0.0.1:8080/v1 \
  -Dakka.persistence.cassandra.events-by-tag.first-time-bucket=20210401T00:00 \
  -Dakka.persistence.cassandra.events-by-tag.eventual-consistency-delay=4s \
  -Dakka.persistence.cassandra.query.refresh-interval=1s \
  -Dakka.cluster.distributed-data.durable.lmdb.dir=/tmp/delta-cache3 \
  -Dkamon.prometheus.embedded-server.hostname="127.0.0.3" \
  -Dkamon.status-page.listen.hostname="127.0.0.3"
```

Start the Fusion web app (optional):

```
docker run --name fusion \
  -e HOST_NAME="http://localhost:8000" \
  -e CLIENT_ID="nexus-web" \
  -e API_ENDPOINT="http://localhost:8080/v1" \
  -p 8000:8000 \
  -d bluebrain/nexus-web:development
```

Run the tests with a custom configuration that sets the following VM options:
```
-D"delta-url"="localhost:8080"
-D"keycloak-url"="localhost:8081"
-D"elasticsearch-url"="localhost:9200"
```