# Blazegraph

Nexus uses @link:[Blazegraph](https://blazegraph.com/){ open=new } as an RDF (triple) store to provide a advanced querying
capabilities on the hosted data. This store is treated as a specialized index on the data so as with
Elasticsearch in case of failures, the system can be fully restored from the primary store. While the technology is
advertised to support @link:[High Availability](https://github.com/blazegraph/database/wiki/HAJournalServer){ open=new } and
@link:[Scaleout](https://github.com/blazegraph/database/wiki/ClusterGuide){ open=new } deployment configurations, we have yet to be able
to setup a deployment in this fashion.

Blazegraph can be deployed by:
* using the docker image created by BBP https://hub.docker.com/r/bluebrain/blazegraph-nexus
* deploying Blazegraph using the prepackaged _tar.gz_ distribution available to download from
  @link:[GitHub](https://github.com/blazegraph/database/releases/tag/BLAZEGRAPH_2_1_6_RC){ open=new }.

@@@ note

We're looking at alternative technologies and possible application level (within Nexus) sharding and replicas.

Blazegraph is not actively maintained anymore since Amazon hired the development team for their Neptune product.
It makes operating it harder as resources about monitoring are scarce and bugs and issues about Blazegraph will remain
unsolved.

@@@

## Running and monitoring
**CPU:**
It suggests heavy indexing and query operations.
It is likely to happen when reindexing after updating search but if it happens regularly:
* Review the SPARQL queries made to Blazegraph
* Review the indexing strategy
* Allocate more resources to Blazegraph
* The Blazegraph and composite views plugin can point to different Blazegraph instances, this can be considered
  if you have a heavy usage of both plugins

**Memory and garbage collection:**
Blazegraph will use the available RAM in 2 ways, JVM heap and the file system cache
so like Elasticsearch, the JVM garbage collection frequency and duration are also important to monitor.

**Storage:**

Blazegraph stores data in an append only journal which means updates will use additional disk space.

So the disk usage will grow with time depending on the rhythm of updates.

Compactions can be applied to the journal using the
@link:[CompactJournalUtility](https://github.com/blazegraph/database/blob/master/bigdata-core/bigdata/src/java/com/bigdata/journal/CompactJournalUtility.java){ open=new }
to reduce the disk usage, but it takes quite a bit a time and requires taking the software offline during the process.

An alternative can be to reindex on a fresh instance of Blazegraph, this approach also allows to reconfigurer the
underlying namespaces.

**Query and indexing performance:**

Query load and latency must be monitored to make sure that information in a timely manner to the client.

On the write side, indexing load and latency must also be watched especially
if the data must be available for search as soon as possible.

To help with this, Delta records long queries and stores them in the `blazegraph_queries` table and also leverage
@link:[Kamon tracing](https://kamon.io/docs/latest/core/tracing/) with spans to measure these operations
and if any of them fail.

## Tools and resources

The @link:[Hardware Configuration](https://github.com/blazegraph/database/wiki/Hardware_Configuration){ open=new } section in the
documentation gives a couple of hints about the requirements to operate Blazegraph and there are additional sections
for optimizations in terms of @link:[Performance](https://github.com/blazegraph/database/wiki/PerformanceOptimization){ open=new },
@link:[IO](https://github.com/blazegraph/database/wiki/IOOptimization){ open=new } and
@link:[Query](https://github.com/blazegraph/database/wiki/QueryOptimization){ open=new }.

The Nexus repository gives also:
* @link:[A jetty configuration](https://github.com/BlueBrain/nexus/blob/master/tests/docker/config/blazegraph/jetty.xml)
  allowing which allow to tune Blazegraph so as to handle better Nexus indexing/querying
* @link:[A log4j configuration](https://github.com/BlueBrain/nexus/blob/master/tests/docker/config/blazegraph/log4j.properties)
  As the default one provided by Blazegraph is insufficient
* @link:[The docker compose file](https://github.com/BlueBrain/nexus/blob/master/tests/docker/docker-compose.yml#L130)
  for tests shows how to configure those files via system properties
* @link:[A python script](https://github.com/BlueBrain/nexus/blob/master/blazegraph/prometheus-exporter/prometheus-blazegraph-exporter.py)
  allowing to scrap Blazegraph metrics so as to push them to a Prometheus instance
