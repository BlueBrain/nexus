# Blazegraph docker

## Building the image

The Dockerfile to build the Blazegraph image used in the Nexus deployment

To build for multi-platforms, run:
```
docker buildx build --platform linux/amd64,linux/arm64/v8 -t bluebrain/blazegraph-nexus:[version] --push .
```

NB: containerd probably needs to be enabled in Docker Desktop.

## Running blazegraph

An example is provided in the integration tests where:
* Java options
* A log4j configuration
* A jetty configuration

Are provided.