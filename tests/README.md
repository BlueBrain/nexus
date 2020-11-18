# Nexus integration tests

Starts the delta ecosystem with docker-compose and run tests on it

Relies on sbt-docker-compose: 
https://github.com/Tapad/sbt-docker-compose

To run the all the tests:
```sbtshell
dockerComposeTest skipBuild
```

To reuse a docker-compose instance:
```
dockerComposeUp skipBuild
```
Which will gives an instance id to run tests:
```sbtshell
dockerComposeTest <instance_id>
```
All tests are designed to be run several times in a row without having to start and stop the docker-compose instance

To run just some tests, we can just provide tags:
```sbtshell
dockerComposeTest <instance_id> -tags:tag1,tag2
```

The available tags are:
* Realms
* Permissions
* Acls
* Orgs
* Projects
* Archives
* Resources
* Views
* CompositeViews
* Events
* Storage
* AppInfo

## Debugging

Start dependencies as containers locally:

```
docker run --name cassandra \
  -e JVM_OPTS="-Xms1g -Xmx1g -Dcassandra.skip_wait_for_gossip_to_settle=0" \
  -e MAX_HEAP_SIZE="1G" \
  -e HEAP_NEWSIZE="100m" \
  -p 9042:9042 \
  -d cassandra:3.11.8

docker run --name keycloak \
  -e KEYCLOAK_USER="admin" \
  -e KEYCLOAK_PASSWORD="admin" \
  -e KEYCLOAK_FRONTEND_URL="http://localhost:8081/auth" \
  -p 8081:8080 \
  -d jboss/keycloak:11.0.1

docker run --name elasticsearch \
  -e ES_JAVA_OPTS="-Xmx1G" \
  -e discovery.type="single-node" \
  -e bootstrap.memory_lock="true" \
  -p 9200:9200 \
  -d docker.elastic.co/elasticsearch/elasticsearch:7.9.1
```

Package the service:

```
sbt app/universal:stage
```

Run the service in debug mode:

```
delta/app/target/universal/stage/bin/delta-app \
  -jvm-debug 5005 \
  -Dapp.http.interface="0.0.0.0" \
  -Dapp.http.base-uri="http://localhost:8080/v1" \
  -Dakka.persistence.cassandra.journal.keyspace-autocreate="true" \
  -Dakka.persistence.cassandra.journal.tables-autocreate="true" \
  -Dakka.persistence.cassandra.events-by-tag.first-time-bucket="20201008T00:00" \
  -Dakka.persistence.cassandra.events-by-tag.pubsub-notification="true" \
  -Dakka.persistence.cassandra.snapshot.keyspace-autocreate="true" \
  -Dakka.persistence.cassandra.snapshot.tables-autocreate="true" \
  -Ddatastax-java-driver.basic.contact-points.1="localhost:9042"
```

Attach the IDE to the debug port 5005 (Remote Application) and start the debug process.

Run the tests with a custom configuration that sets the following VM options:
```
-D"delta:8080"="localhost:8080"
-D"keycloak:8080"="localhost:8081"
-D"elasticsearch:9200"="localhost:9200"
```