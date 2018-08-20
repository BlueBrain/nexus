# On premise / cloud deployment

There are several things to consider when preparing to deploy Nexus "on premise" because the setup depends a lot on the
various usage profiles, but the most important categories would be:

*   Availability
*   Latency & throughput
*   Capacity
*   Efficient use of hardware resources
*   Backup and restore
*   Monitoring & alerting

Each of the Nexus services and "off the shelf" products can be deployed as a single instance or as a cluster (with one
exception at this point being BlazeGraph which doesn't come with a clustering option). The advantages for deploying
clusters are generally higher availability, capacity and throughput at the cost of higher latency, consistency and
having to potentially deal with network instability.

The decision to go with single node deployments or clustered deployments can be revisited later on and mixed setups
(some services single node while others clustered) are also possible.

The Nexus distribution is made up of docker images which can be run on any host operating system and each of the "off
the shelf" products also offer docker as a deployment option. We would generally recommend using a container
orchestration solution like [Kubernetes](https://kubernetes.io/), [OpenShift](https://www.openshift.com/) or
[Docker Swarm](https://docs.docker.com/engine/swarm/) as they offer good management capabilities, discovery, load
balancing and self-healing. They also accommodate changes in hardware allocations for the deployments, changes that can
occur due to evolving usage patterns, software updates etc. Currently the biggest Nexus deployment is at EPFL within
OpenShift.

## Choice of hardware

Depending on the target throughput, usage profiles and data volume the hardware specification can vary greatly; please
take a look at the [benchmarks section](../../benchmarks/index.html) to get an idea of what you should expect in terms
of throughput with various hardware configurations. When the usage profiles are unknown a couple of rules of thumb
should narrow the scope:

1.  Nexus uses a collection of data stores ([Cassandra](http://cassandra.apache.org/),
    [ElasticSearch](https://www.elastic.co/products/elasticsearch), [BlazeGraph](https://www.blazegraph.com/)) which
    depend performance wise to the underlying disk access, so:
    *   prefer local storage over network storage for lower latency when doing IO,
    *   prefer SSD over HDDs because random access speed is more important than sequential access,
    *   one exception is the file storage ([attachments to resources](../../api/kg-service-api.html) which are stored as
        binary blobs on the filesystem) where the network disks should not be a cause for concern, nor random access
        speed; this assumes that accessing attachments is not the at the top in the usage profile
2.  All of Nexus services and most of the "off the shelf" products are built to run on top of the JVM which usually
    require more memory over computing power. A rough ratio of 2 CPU cores per 8GB of RAM is probably a good one (this
    of course depends on the CPU specification).
3.  Due to the design for scalability of Nexus services and "off the shelf" products the network is a very important
    characteristic of the deployment as frequent dropped packets or
    [network partitions](https://en.wikipedia.org/wiki/Network_partition) can seriously affect the availability of the
    system. Clustered / distributed systems generally use some form of
    [consensus](https://en.wikipedia.org/wiki/Consensus_\(computer_science\)) which is significantly affected by
    the reliability of the network. If the reliability of the network is a concern within the target deployment then
    vertical scalability is desirable over horizontal scalability: fewer host nodes with better specifications is better
    over more commodity hardware host nodes.

## Cassandra

Nexus uses [Cassandra](http://cassandra.apache.org/) as its _primary store_ as it scales well in terms of reads with the
number of nodes in the cluster. It offers data replication out of the box, which allows the system to continue to be
available in case of node failures or network partitions.

Since this is the _primary store_ it is the most important system to be
[backed up](https://docs.datastax.com/en/cassandra/3.0/cassandra/operations/opsBackupRestore.html). All of the data
that Nexus uses in other stores can be recomputed from the one stored in Cassandra as the other stores are used as
mere indexing systems.

Please have a look at the [Planning and Testing](https://docs.datastax.com/en/dse-planning/doc/) section in the
DataStax documentation as it contains recommendations in terms of hardware and capacity.

As described in the [architecture section](../../architecture/systematic-service-design.html) the generally adopted
persistence model is an EventSourced model in which the data store is used as an _append only_ store. This has
implications to the total amount of disk used by the primary store.

A formula for computing the required disk space:
```
total = (resource_size + nexus_metadata_size) * count * number_updates * replication_factor * 2 (compaction requirement)
```

The `nexus_metadata_size` varies depending on many things, but it's generally less than or equal to the `resource_size`.

An example, assuming:

*   10KB per resource
*   1.000.000 distinct resources
*   10 updates per resource
*   replication factor of 3

... the total required disk size would be:
```
(10KB + 10KB) * 1.000.000 * 10 * 3 * 2 = 1.000.000.000KB ~= 955GB
```
The resulting size represents the total disk space of the cluster; a 5 node cluster with the data volume in the
example above would have to be configured with 200GB disks per node.

## ElasticSearch

Nexus uses [ElasticSearch](https://www.elastic.co/products/elasticsearch) to host several _system_ indices and _user
defined_ ones. It offers sharding and replication out of the box. Deciding whether this system requires backup depends
on the tolerated time for a restore. Nexus can be instructed to rebuild all indices using the data from the _primary
store_, but being an incremental indexing process it can take longer than restoring from a backup. Since it can be
configured to host a number of replicas for each shard it can tolerate a number of node failures.

The ElasticSearch [setup documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/setup.html)
contains the necessary information on how to install and configure it, but recommendations on sizing the nodes and
cluster are scarce because it depends on usage.

A formula for computing the required disk space:
```
total = (resource_size * count * documents + lucene_index) * replication_factor
```
... where the `lucene_index` while it can vary should be less than twice the size of the original documents.

An example, assuming:

*   10KB per resource
*   1.000.000 distinct resources
*   3 documents per resource (the number of documents depends on the configured [views](../../api/kg-service-api.html)
    in the system)
*   2 additional shard replicas (replication factor of 3)

... the total required disk size would be:
```
(10KB * 1.000.000 * 3 + 2 * (10KB * 1.000.000 * 3)) * 3 = 270.000.000KB ~= 260GB
```
The resulting size represents the total disk space of the data nodes in the cluster; a 5 data node cluster with the data
volume in the example above would have to be configured with 60GB disks per node.

## Kafka and ZooKeeper

Nexus uses [Kafka](https://kafka.apache.org/) for asynchronous communication between services, usually exposing the
event log directly with simple transformations (internal service event representation to public representation). Kafka
is also used to provide an integration point such that custom / specialized services can be built to run on top of
Nexus. It offers replication out of the box and while the event log can be rebuilt from the _primary store_ whether it
requires backup or not is a decision that depends on how quickly a restore needs to be performed in case of failure.

The system is not used at its full potential in terms of throughput, a small cluster will work on most Nexus
deployments. It was chosen because of its similarity with the Nexus service event based
[persistence model](../../architecture/systematic-service-design.html). 

Nexus doesn't use [ZooKeeper](https://zookeeper.apache.org/) directly, but just as a dependency for Kafka which in turn
is used solely for coordinating the Kafka cluster. A 3 node 0.5 CPU / 1.5GB RAM cluster should be sufficient for most
use cases.

The Kafka [configuration section](https://kafka.apache.org/documentation/#config) in its
[documentation](https://kafka.apache.org/documentation/) list a series of recommendations and instructions for a
production deployment.

The ZooKeeper's [Getting Started](https://zookeeper.apache.org/doc/current/zookeeperStarted.html) and
[Administrator](https://zookeeper.apache.org/doc/current/zookeeperAdmin.html) guides are a good place to start.

Nexus pushes to Kafka each service event log, so the allocated disk space is an important aspect to take into
consideration. As with previous sizing instructions the disk size depends on the number of resources, their size and the
configured replication factor:
```
total = (resource_size + nexus_metadata_size) * count * replication_factor
```

An example, assuming:

*   10KB per resource
*   1.000.000 distinct resources
*   10 updates per resource
*   replication factor of 3

... the total required disk size would be:
```
(10KB + 10KB) * 1.000.000 * 3 = 60.000.000KB ~= 60GB
```
The resulting size represents the total disk space of the cluster; a 3 data node cluster with the data volume in the
example above would have to be configured with 20GB disks per node.

@@@ note

Nexus stores its entire event log in Kafka so it's important to configure Kafka with _permanent log retention_. Make
sure the following configuration values are set:
```
log.retention.bytes=-1
log.retention.hours=-1
```

@@@

## BlazeGraph

Nexus uses [BlazeGraph](https://www.blazegraph.com/) as an RDF (triple) store to provide a advanced querying
capabilities on the hosted data. This store is treated as a specialized index on the data so as with Kafka and
ElasticSearch in case of failures, the system can be fully restored from the primary store. While the technology is
advertised to support [High Availability](https://wiki.blazegraph.com/wiki/index.php/HAJournalServer) and
[Scaleout](https://wiki.blazegraph.com/wiki/index.php/ClusterGuide) deployment configurations, we have yet to be able
to setup a deployment in this fashion.

We currently recommend deploying BlazeGraph using the prepackaged _tar.gz_ distribution available to download from
[sourceforge](https://sourceforge.net/projects/bigdata/files/bigdata/2.1.4/).

@@@ note

We're looking at alternative technologies and possible application level (within Nexus) sharding and replicas.

@@@

The [Hardware Configuration](https://wiki.blazegraph.com/wiki/index.php/Hardware_Configuration) section in the
documentation gives a couple of hints about the requirements to operate BlazeGraph and there are additional sections
for optimizations in terms of [Performance](https://wiki.blazegraph.com/wiki/index.php/PerformanceOptimization),
[IO](https://wiki.blazegraph.com/wiki/index.php/IOOptimization) and
[Query](https://wiki.blazegraph.com/wiki/index.php/QueryOptimization).

BlazeGraph stores data in an append only journal which means updates will use additional disk space.

A formula for computing the required disk space:
```
total = (resource_triples + nexus_triples) * count * number_updates * triple_size + lucene_index
```
... where the `lucene_index` while it can vary should be less than twice the size of the original documents. 

An example, assuming:

*   100 triples (rough estimate for a 10KB json-ld resource representation)
*   20  additional nexus triples on average
*   1.000.000 distinct resources
*   10 updates per resource
*   200 bytes triple size (using quads mode)

... the total required disk size would be:
```
(100 + 20) * 1.000.000 * 10 * 200 / 1024 * 3 ~= 700.000.000KB ~= 670GB
```

Compactions can be applied to the journal using the
[CompactJournalUtility](https://github.com/blazegraph/database/blob/master/bigdata-core/bigdata/src/java/com/bigdata/journal/CompactJournalUtility.java)
to reduce the disk usage, but it take quite a bit a time and requires taking the software offline during the process.

