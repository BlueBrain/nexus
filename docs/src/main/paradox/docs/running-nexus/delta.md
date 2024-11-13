# Nexus Delta

## Running and monitoring

**CPU:**
It suggests heavy indexing and read operations.
It is likely to happen when reindexing a large amount of views at the same time but if it happens regularly:

* Review which indexing process is going on by querying the `projection_offsets` and `composite_offsets` table
* Allocate more resources to Delta

**Memory and garbage collection:**
Delta will use the available RAM in 2 ways, JVM heap and the file system cache
so like Elasticsearch, the JVM garbage collection frequency and duration are also important to monitor.

@@@ note
JVM and host metrics (including those related to the heap) are collected by @link:[Kamon](https://kamon.io/)
@@@

**Storage:**
The different types of storage (local, S3) need to be monitored for availability/performance/utilization

**Metrics:**
Nexus Delta relies on @link:[Kamon](https://kamon.io/) to also collect metrics about the execution of most operations
(fetching a resource, query Blazegraph, etc...)

To enable Kamon in Delta, the `KAMON_ENABLED` env variable must be set to true.

To monitor Nexus write activity like resource and file creation/updates, a dashboard is available in the 
@link:[Nexus repo](https://github.com/BlueBrain/nexus/blob/$git.branch$/kibana/event-metrics/general.ndjson).

**Logs:**
Nexus Delta relies on @link:[Logback](https://logback.qos.ch/) for logs which one of the popular logging
frameworks on the JVM.

Logback provides:

* Reloading the configuration while the application is running
* Control the output of the logs, opting for JSON helps for the integration with Filebeats and Elasticsearch
  for log aggregation

An example of logback configuration writing logs as json in files is available 
@link:[here](configuration/logback.xml){ open=new }
