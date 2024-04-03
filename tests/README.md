# Nexus integration tests

Starts the delta ecosystem with docker compose and run tests on it

First, run:
```shell
docker compose -f docker/docker-compose.yml up -d
```

Add the following local domains to your `/etc/hosts` file for `S3StorageAccessSpec`:
```
127.0.0.1 bucket.my-domain.com
127.0.0.1 other.my-domain.com
127.0.0.1 bucket2.my-domain.com
127.0.0.1 bucket3.my-domain.com
```

To run all the tests:
```sbtshell
test
```

To run just some tests:
```sbtshell
testOnly *RemoteStorageSpec
```
