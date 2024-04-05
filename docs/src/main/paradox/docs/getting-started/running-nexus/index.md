@@@ index

- @ref:[Configuration](configuration/index.md)
- @ref:[Search Configuration](search-configuration.md)

@@@

# Running Nexus

If you wish to quickly try out Nexus, we provide a @ref:[public sandbox](#using-the-public-sandbox). 
For a more in-depth test-drive of Nexus on your machine, we recommend the @ref:[Docker Compose approach](#docker-compose). 
For a production deployment on your in-house or cloud infrastructure, please refer to our @ref:[deployment guide](#on-premise-cloud-deployment).

## Using the public sandbox

A public instance of Nexus is running at @link:[https://sandbox.bluebrainnexus.io](https://sandbox.bluebrainnexus.io){ open=new }. 
You can log in with a GitHub account. It's provided for evaluation purposes only, without any guarantees.

The API root is @link:[https://sandbox.bluebrainnexus.io/v1](https://sandbox.bluebrainnexus.io/v1){ open=new }.

@@@ note { .warning }

Do not ingest any proprietary or sensitive data. The environment will be wiped regularly, your data and credentials
can disappear anytime.

@@@

## Run Nexus locally with Docker

### Requirements

#### Docker

Regardless of your OS, make sure to run a recent version of the Docker Engine. 
This was tested with version **20.10.23**.
The Docker Engine, along the Docker CLI, come with an installation of Docker Desktop. Visit the @link:[official Docker Desktop documentation](https://docs.docker.com/desktop/) for detailed installation steps.

Command
:
```shell
docker --version
```

Example
:
```shell
$ docker --version
Docker version 20.10.23, build 7155243
```

#### Memory and CPU limits

On macOS and Windows, Docker effectively runs containers inside a VM created by the system hypervisor.
Nexus requires at least **2 CPUs** and **8 GiB** of memory in total. You can increase the limits
in Docker settings in the menu *Settings* > *Resources*.

For a proper evaluation using Docker, we recommend allocating at least **16GiB** of RAM to
run the provided templates. Feel free to tweak memory limits in order to fit your hardware constraints. At the cost
of a slower startup and a decreased overall performance, you should be able to go as low as:

|    Service    | Memory [MiB] |
|:-------------:|:------------:|
|  PostgreSQL   |          512 |
| Elasticsearch |          512 |
|  Blazegraph   |         1024 |
|     Delta     |         1024 |

### Docker Compose

#### Set-up

* Download the @link:[Docker Compose template](docker/docker-compose.yaml){ open=new } into a directory of your choice, for instance
`~/docker/nexus/`.
* Download the @link:[Delta configuration](docker/delta.conf){ open=new } to the same directory.
* Download the @link:[http proxy configuration](docker/nginx.conf){ open=new } to the same directory.

#### Starting Nexus

From within the directory that contains the `docker-compose.yaml` you downloaded, run the containers using Docker Compose:

Command
:
```shell
docker compose --project-name nexus --file=docker-compose.yaml up --detach
```

Example
:
```shell
$ cd ~/docker/nexus
$ docker compose --project-name nexus --file=docker-compose.yaml up --detach
...
⠿ Network nexus_default             Created 0.0s
⠿ Container nexus-elasticsearch-1   Started 1.2s
⠿ Container nexus-postgres-1        Started 1.2s
⠿ Container nexus-blazegraph-1      Started 1.1s
⠿ Container nexus-delta-1           Started 1.6s
⠿ Container nexus-web-1             Started 1.8s
⠿ Container nexus-router-1          Started 2.1s
```

When running the command for the first time, Docker will pull all necessary images from Dockerhub if they are not available locally. Once all containers are running, wait one or two minutes and you should be able to access Nexus locally, on the port 80:

Command
:
```shell
curl http://localhost/v1/version
```

Example
:
```shell
$ curl http://localhost/v1/version | jq
{
  "@context": "https://bluebrain.github.io/nexus/contexts/version.json",
  "delta": "1.10.0",
  "dependencies": {
    "blazegraph": "2.1.6-SNAPSHOT",
    "elasticsearch": "8.13.0",
    "postgres": "15.6"
  },
  "environment": "dev",
  "plugins": {
    "archive": "1.10.0",
    "blazegraph": "1.10.0",
    "composite-views": "1.10.0",
    "elasticsearch": "1.10.0",
    "storage": "1.10.0"
  }
}
```

#### Using Fusion

Fusion can be accessed by opening `http://localhost` in your web browser. You can start by creating an organization from the `http://localhost/admin` page.

@@@ note { .warning }

This setup runs the Nexus ecosystem without an identity provider, and the anonymous user is given all default permissions; do not publicly expose the endpoints of such a deployment.

@@@

#### Administration

To list running services or access logs, please refer to the official Docker
@link:[documentation](https://docs.docker.com/engine/reference/commandline/stack/){ open=new }.

#### Stopping Nexus

You can stop and delete the entire deployment with:

Command
:
```shell
docker compose --project-name nexus down --volumes
```

Example
:
```shell
$ docker compose --project-name nexus down --volumes
[+] Running 7/7
⠿ Container nexus-router-1         Removed  0.2s
⠿ Container nexus-web-1            Removed  0.3s
⠿ Container nexus-delta-1          Removed  0.5s
⠿ Container nexus-postgres-1       Removed  0.3s
⠿ Container nexus-blazegraph-1     Removed  10.3s
⠿ Container nexus-elasticsearch-1  Removed  1.0s
⠿ Network nexus_default            Removed  0.1s
```

@@@ note

As no data is persisted outside the containers, **everything will be lost** once you shut down the Nexus
deployment. If you'd like help with creating persistent volumes, feel free to contact us on
@link:[Github Discussions](https://github.com/BlueBrain/nexus/discussions){ open=new }.

@@@

### Endpoints

The provided reverse proxy (the `nginx` image) exposes several endpoints:

* [root](http://localhost): Nexus Fusion
* [v1](http://localhost/v1): Nexus Delta
* [elasticsearch](http://localhost/elasticsearch): Elasticsearch endpoint
* [blazegraph](http://localhost/blazegraph): Blazegraph web interface

If you'd like to customize the listening port or remove unnecessary endpoints, you can simply modify the `nginx.conf`
file.

### PostgreSQL partitioning 

Nexus Delta takes advantage of PostgreSQL's @link:[Table Partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html) feature. This allows for improved query performance, and facilitates loading, deleting, or transferring data.

The `public.scoped_events` and `public.scoped_states` are partitioned by organization, which is itself partitioned by the projects it contains; this follows the natural hierarchy that can be found in Nexus Delta.

Nexus Delta takes care of handling the creation and deletion of the partitions.

* If the created project is the first one of a given organization, both the organization partition and the project subpartition will be created.
* If the organization partition already exist, then only the project subpartition will be created upon project creation.

The naming scheme of the (sub)partitions is as follows:

`{table_name}_{MD5_org_hash}` for organization partitions

`{table_name}_{MD5_project_hash}` for project partition

where

* `{table_name}` is either `scoped_events` or `scoped_states`
* `{MD5_org_hash}` is the MD5 hash of the organization name
* `{MD5_project_has}` is the MD5 hash of the project reference (i.e. has the form `{org_name}/{project_name}`)

MD5 hashing is used in order to guarantee a constant partition name length (PostgreSQL table names are limited to 63 character by default), as well as to avoid any special characters that might be allowed in project names but not in PostgreSQL table names (such as `-`).

Example:

You create the organization called `myorg`, inside of which you create the `myproject` project. When the project is created, Nexus Delta will have created the following partitions:

* `scoped_events_B665280652D01C4679777AFD9861170C`, the partition of events from the `myorg` organization
    * `scoped_events_7922DA7049D5E38C83053EE145B27596`, the subpartition of the events from the `myorg/myproject` project
* `scoped_states_B665280652D01C4679777AFD9861170C`, the partition of states from the `myorg` organization
    * `scoped_states_7922DA7049D5E38C83053EE145B27596`, the subpartition of the states from the `myorg/myproject` project

#### Advanced subpartitioning

While Nexus Delta provides table partitioning out-of-the-box, it is primarily addressing the case where the data is more or less uniformly spread out across multiple projects. If however there is one or more project that are very large, it is possible to add further subpartitions according to a custom rule. This custom subpartitioning must be decided on a case-by-case basis using your knowledge of the given project; the idea is to create uniform partitions of your project. Please refer to the @link:[PostgreSQL Table Partitioning documentation](https://www.postgresql.org/docs/current/ddl-partitioning.html).

## On premise / cloud deployment

There are several things to consider when preparing to deploy Nexus "on premise" because the setup depends a lot on the
various usage profiles, but the most important categories would be:

*   Availability
*   Latency & throughput
*   Capacity
*   Efficient use of hardware resources
*   Backup and restore
*   Monitoring & alerting

Each of the Nexus services and "off the shelf" products can be deployed as a single instance or as a cluster (with one
exception at this point being Blazegraph which doesn't come with a clustering option). The advantages for deploying
clusters are generally higher availability, capacity and throughput at the cost of higher latency, consistency and
having to potentially deal with network instability.

The decision to go with single node deployments or clustered deployments can be revisited later on and mixed setups
(some services single node while others clustered) are also possible.

The Nexus distribution is made up of Docker images which can be run on any host operating system and each of the "off
the shelf" products that also offer Docker as a deployment option. We would generally recommend using a container
orchestration solution like @link:[Kubernetes](https://kubernetes.io/){ open=new } as it offers good management capabilities, discovery, load
balancing and self-healing. They also accommodate changes in hardware allocations for the deployments, changes that can
occur due to evolving usage patterns, software updates etc. Currently, the largest Nexus deployment is at EPFL and runs on Kubernetes.


### Choice of hardware

Depending on the target throughput, usage profiles and data volume the hardware specification can vary greatly; please
take a look at the @ref:[benchmarks section](../../delta/benchmarks/v1.4.2.md) to get an idea of what you should expect in terms
of throughput with various hardware configurations. When the usage profiles are unknown a couple of rules of thumb
should narrow the scope:

1.  Nexus uses a collection of data stores (@link:[PostgreSQL](https://www.postgresql.org/){ open=new },
    @link:[Elasticsearch](https://www.elastic.co/elasticsearch){ open=new }, 
    @link:[Blazegraph](https://blazegraph.com/){ open=new }) which depend performance wise to the underlying disk 
    access, so:
    *   prefer local storage over network storage for lower latency when doing IO,
    *   prefer SSD over HDDs because random access speed is more important than sequential access,
    *   one exception is the file storage (@ref:[file resources](../../delta/api/files-api.md) which are stored as
        binary blobs on the filesystem) where the network disks should not be a cause for concern, nor random access
        speed; this assumes that accessing attachments is not the at the top in the usage profile
2.  All of Nexus services and most of the "off the shelf" products are built to run on top of the JVM which usually
    require more memory over computing power. A rough ratio of 2 CPU cores per 8GB of RAM is probably a good one (this
    of course depends on the CPU specification).
3.  Due to the design for scalability of Nexus services and "off the shelf" products the network is a very important
    characteristic of the deployment as frequent dropped packets or
    @link:[network partitions](https://en.wikipedia.org/wiki/Network_partition){ open=new } can seriously affect the 
    availability of the system. Clustered / distributed systems generally use some form of
    @link:[consensus](https://en.wikipedia.org/wiki/Consensus_%28computer_science%29)){ open=new } which is significantly affected by
    the reliability of the network. If the reliability of the network is a concern within the target deployment then
    vertical scalability is desirable over horizontal scalability: fewer host nodes with better specifications is better
    over more commodity hardware host nodes.

### PostgreSQL

Nexus uses @link:[PostgreSQL](https://www.postgresql.org/){ open=new } as its _primary store_ as for its strong reputation for performance, reliability and flexibility.
It can also be run in different contexts from integration to 

Since this is the _primary store_ it is the most important system to be
@link:[backed up](https://www.postgresql.org/docs/current/backup.html){ open=new }. All of the data
that Nexus uses in other stores can be recomputed from the one stored in PostgreSQL as the other stores are used as
mere indexing systems.

// TODO capacity planning + recommendations

As described in the @ref:[architecture section](../../delta/architecture.md) the generally adopted
persistence model is an EventSourced model in which the data store is used as an _append only_ store. This has
implications to the total amount of disk used by the primary store.

// TODO formula computing disk space

### Elasticsearch

Nexus uses @link:[Elasticsearch](https://www.elastic.co/elasticsearch){ open=new } to host several _system_ indices and _user
defined_ ones. It offers sharding and replication out of the box. Deciding whether this system requires backup depends
on the tolerated time for a restore. Nexus can be instructed to rebuild all indices using the data from the _primary
store_, but being an incremental indexing process it can take longer than restoring from a backup. Since it can be
configured to host a number of replicas for each shard it can tolerate a number of node failures.

The Elasticsearch @link:[setup documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/setup.html){ open=new }
contains the necessary information on how to install and configure it, but recommendations on sizing the nodes and
cluster are scarce because it depends on usage.

A formula for computing the required disk space:
```text
total = (resource_size * count * documents + lucene_index) * replication_factor
```
... where the `lucene_index` while it can vary should be less than twice the size of the original documents.

An example, assuming:

*   10KB per resource
*   1.000.000 distinct resources
*   3 documents per resource (the number of documents depends on the configured @ref:[views](../../delta/api/views/index.md)
    in the system)
*   2 additional shard replicas (replication factor of 3)

... the total required disk size would be:
```text
(10KB * 1.000.000 * 3 + 2 * (10KB * 1.000.000 * 3)) * 3 = 270.000.000KB ~= 260GB
```
The resulting size represents the total disk space of the data nodes in the cluster; a 5 data node cluster with the data
volume in the example above would have to be configured with 60GB disks per node.

### Blazegraph

Nexus uses @link:[Blazegraph](https://blazegraph.com/){ open=new } as an RDF (triple) store to provide a advanced querying
capabilities on the hosted data. This store is treated as a specialized index on the data so as with Kafka and
Elasticsearch in case of failures, the system can be fully restored from the primary store. While the technology is
advertised to support @link:[High Availability](https://github.com/blazegraph/database/wiki/HAJournalServer){ open=new } and
@link:[Scaleout](https://github.com/blazegraph/database/wiki/ClusterGuide){ open=new } deployment configurations, we have yet to be able
to setup a deployment in this fashion.

We currently recommend deploying Blazegraph using the prepackaged _tar.gz_ distribution available to download from
@link:[GitHub](https://github.com/blazegraph/database/releases/tag/BLAZEGRAPH_2_1_6_RC){ open=new }.

@@@ note

We're looking at alternative technologies and possible application level (within Nexus) sharding and replicas.

@@@

The @link:[Hardware Configuration](https://github.com/blazegraph/database/wiki/Hardware_Configuration){ open=new } section in the
documentation gives a couple of hints about the requirements to operate Blazegraph and there are additional sections
for optimizations in terms of @link:[Performance](https://github.com/blazegraph/database/wiki/PerformanceOptimization){ open=new },
@link:[IO](https://github.com/blazegraph/database/wiki/IOOptimization){ open=new } and
@link:[Query](https://github.com/blazegraph/database/wiki/QueryOptimization){ open=new }.

Blazegraph stores data in an append only journal which means updates will use additional disk space.

A formula for computing the required disk space:
```text
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
```text
(100 + 20) * 1.000.000 * 10 * 200 / 1024 * 3 ~= 700.000.000KB ~= 670GB
```

Compactions can be applied to the journal using the
@link:[CompactJournalUtility](https://github.com/blazegraph/database/blob/master/bigdata-core/bigdata/src/java/com/bigdata/journal/CompactJournalUtility.java){ open=new }
to reduce the disk usage, but it takes quite a bit a time and requires taking the software offline during the process.
