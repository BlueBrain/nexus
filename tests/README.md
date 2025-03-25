# Nexus integration tests

Starts the delta ecosystem with docker compose and run tests on it

First, run:
```shell
docker compose -f docker/delta-blazegraph-compose.yml up -d
```

To run all the tests:
```sbtshell
test
```

To run just some tests:
```sbtshell
testOnly *RemoteStorageSpec
```
