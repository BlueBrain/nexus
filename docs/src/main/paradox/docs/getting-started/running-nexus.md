# Running Nexus

If you wish to quickly try out Nexus, we provide a @ref:[public sandbox](#using-the-public-sandbox). 
For a more in-depth test-drive of Nexus on your machine, we recommend the @ref:[Docker Swarm approach](#recommended-docker-swarm). 
For a production deployment on your in-house or cloud infrastructure, please refer to our @ref:[deployment guide](#on-premise-cloud-deployment).

## Using the public sandbox

A public instance of Nexus is running at @link:[https://sandbox.bluebrainnexus.io/web](https://sandbox.bluebrainnexus.io/web){ open=new }. 
You can log in with a GitHub account. It's provided for evaluation purposes only, without any guarantees.

The API root is @link:[https://sandbox.bluebrainnexus.io/v1](https://sandbox.bluebrainnexus.io/v1){ open=new }.

@@@ note

Do not ingest any proprietary or sensitive data. The environment will be wiped regularly, your data and credentials
can disappear anytime.

@@@

## Run Nexus locally with Docker

### Requirements

#### Docker

Regardless of your OS, make sure to run a recent version of Docker (community edition).
This was tested with versions **18.03.1** and above.
You might need to get installation packages directly
from the @link:[official Docker website](https://docs.docker.com/){ open=new } if the one provided by your system
package manager is outdated.

Command
:
```
docker --version
```

Example
:
```
$ docker version
Docker version 18.09.1, build 4c52b90
```

#### Memory and CPU limits

On macOS and Windows, Docker effectively runs containers inside a VM created by the system hypervisor.
Nexus requires at least **2 CPUs** and **8 GiB** of memory in total. You can increase the limits
in Docker settings in the menu *Preferences* > *Advanced*.

For a proper evaluation using Docker Swarm or Minikube/Kubernetes, at least **16GiB** of RAM is needed to run the
provided templates. Feel free to tweak memory limits in order to fit your hardware constraints. At the cost
of a slower startup and a decreased overall performance, you should be able to go as low as:

|    Service    | Memory [MiB] |
|:-------------:|:------------:|
| Cassandra     |          512 |
| Elasticsearch |          512 |
| Blazegraph    |         1024 |
| Delta         |         1024 |

### Recommended: Docker Swarm

Docker Swarm is the native orchestrator shipped with Docker. It provides a more robust way to run Nexus.

If you've never used Docker Swarm or Docker Stacks before, you first need to create a swarm cluster
on your local machine:

Command
:
```
docker swarm init
```

Example
:
```
$ docker swarm init
Swarm initialized: current node (***) is now a manager.
 
To add a worker to this swarm, run the following command:
 
    docker swarm join --token {token} 128.178.97.243:2377
 
To add a manager to this swarm, run 'docker swarm join-token manager' and follow the instructions.
```

#### Deployment

Download the @link:[Docker Compose template](running-nexus/docker-swarm/docker-compose.yaml){ open=new } into a directory of your choice, for instance
`~/docker/nexus/`.

Download the @link:[http proxy configuration](running-nexus/docker-swarm/nginx.conf){ open=new } to the same directory.

#### Starting Nexus

Create a *nexus* deployment with Docker Stacks:

Command
:
```
docker stack deploy nexus --compose-file=docker-compose.yaml
```

Example
:
```
$ cd ~/docker/nexus
$ docker stack deploy nexus --compose-file=docker-compose.yaml
Creating network nexus_default
Creating service nexus_iam
Creating service nexus_admin
Creating service nexus_elasticsearch
Creating service nexus_cassandra
Creating service nexus_blazegraph
Creating service nexus_router
Creating service nexus_kg
```

Wait one or two minutes and you should be able to access Nexus locally, on the port 80:

Command
:
```
curl http://localhost/kg
```

Example
:
```
$ curl http://localhost/version
{"name":"kg","version":"1.1.0"}
```

#### Administration

To list running services or access logs, please refer to the official Docker
@link:[documentation](https://docs.docker.com/engine/reference/commandline/stack/){ open=new }.

Alternatively you can deploy @link:[Swarmpit](https://swarmpit.io/){ open=new } which provides a comprehensive UI
to manage your Docker Swarm cluster.

#### Stopping Nexus

You can stop and delete the entire deployment with:

Command
:
```
docker stack rm nexus
```

Example
:
```
$ docker stack rm nexus
Removing service nexus_admin
Removing service nexus_blazegraph
Removing service nexus_cassandra
Removing service nexus_elasticsearch
Removing service nexus_iam
Removing service nexus_kg
Removing service nexus_router
Removing network nexus_default
```

@@@ note

As no data is persisted outside the containers, **everything will be lost** once you remove the Nexus
deployment. If you'd like help with creating persistent volumes, feel free to contact us on our
@link:[Gitter channel](https://gitter.im/BlueBrain/nexus){ open=new }.

@@@

### Endpoints

The provided reverse proxy (the `nginx` image) exposes several endpoints:

* [root](http://localhost): Nexus web interface
* [v1](http://localhost/v1): API root
* [delta](http://localhost/version): Delta service descriptor
* [elasticsearch](http://localhost/elasticsearch): Elasticsearch endpoint
* [blazegraph](http://localhost/blazegraph): Blazegraph web interface

If you'd like to customize the listening port or remove unnecessary endpoints, you can simply modify the `nginx.conf`
file.

## Run Nexus locally with Minikube

@link:[Minikube](https://github.com/kubernetes/minikube){ open=new } is a tool that makes it easy to run Kubernetes locally. 
Minikube runs a single-node Kubernetes cluster inside a VM on your machine for users looking to try out Kubernetes or 
develop with it day-to-day.

@@@ note

This section makes use of static assets hosted on this website; to remove the clutter please export the base of the
documentation to `$MINI` env var:

```
export MINI="https://bluebrainnexus.io/docs/getting-started/running-nexus/minikube"
```

@@@

@@@ note

This page presents the necessary commands to deploy Nexus with Minikube but also examples the show the expected output.

Some of the examples on this page make use of @link:[curl](https://curl.se/){ open=new } and @link:[jq](https://stedolan.github.io/jq/){ open=new }
for formatting the json output when interacting with the services. Please install these command line tools if you'd like
to run the commands in the examples. They should be available through your usual package manager (Chocolatey, Homebrew,
APT, YUM/DNF, ...)

@@@

### Minikube set-up

#### Installation

Follow the @link:[installation instructions](https://minikube.sigs.k8s.io/docs/start/){ open=new } from 
the official Kubernetes documentation.

#### Run Minikube

To start Minikube run (notice the _cpu_ and _memory_ flags, the setup requires a minimum of `--cpus=2 --memory=8196`):

```
minikube start --cpus 6 --memory 10240 --vm-driver=$DRIVER
```

For better performance we recommended to select the `$DRIVER` corresponding to your OS native hypervisor, namely
_hyperkit_ on macOS, _hyperv_ on Windows and _kvm2_ on Linux.

If the installation is successful you can run the following command to open the
@link:[Kubernetes Dashboard](https://kubernetes.io/docs/tasks/access-application-cluster/web-ui-dashboard/){ open=new }:

```
minikube dashboard
```

To stop Minikube run:

```
minikube stop
```

@@@ note

After stopping minikube the vm still exists on the system; starting minikube again will preserve the deployed services.
To permanently remove minikube vm run:

```
minikube delete
```

@@@

#### Enable the ingress addon

Minikube comes with a collection of addons like the @link:[Kubernetes Dashboard](https://kubernetes.io/docs/tasks/access-application-cluster/web-ui-dashboard/){ open=new } 
but not all are enabled by default. An important one is the _ingress_ addon which enables routing http traffic from the
host into the cluster.

To make sure the _ingress_ addon is enabled run:

Command
:
```
minikube addons enable ingress
```

Example
:
```
$ minikube addons enable ingress
ingress was successfully enabled
$
```

To get the external IP of the cluster (to be used later in accessing services) run:

Command:
:
```
minikube ip
```

Example:
:
```
$ minikube ip
192.168.64.3
$
```

#### Setup a separate namespace

Kubernetes namespaces are logical groupings of resources which allow segregating various deployments in "virtual
clusters".

The default installation of Minikube creates three namespaces: _kube-system_, _kube-public_ and _default_. This example
uses a separate namespace to group Nexus specific resources.

Get the list of available namespaces:

Command
:
```
kubectl get namespaces
```

Example
:
```
$ kubectl get namespaces
NAME          STATUS    AGE
default       Active    1h
kube-public   Active    1h
kube-system   Active    1h
$
```

Create the _nexus_ namespace:

Command
:
```
kubectl apply -f $MINI/namespace.yaml
```

Example
:
```
$ kubectl apply -f $MINI/namespace.yaml
namespace/nexus created
$ kubectl get namespaces
NAME          STATUS    AGE
default       Active    1h
kube-public   Active    1h
kube-system   Active    1h
nexus         Active    1m
$
```

Default the `kubectl` to the _nexus_ namespace:

Command
:
```
kubectl config set-context minikube --namespace=nexus
```

Example
:
```
$ kubectl config set-context minikube --namespace=nexus
Context "minikube" modified.
$
```

@@@ note

Every time Minikube is stopped and started again, the context and its configuration is lost. Remember to run the
following commands every time you start minikube:

```
kubectl config use-context minikube && kubectl config set-context minikube --namespace=nexus
```

@@@

### Deploy dependent services

Nexus uses numerous off the shelf services that need to be set up as a prerequisite. Run the following command to
save the IP address of the minikube cluster in an environment variable:

Command
:
```
export NEXUS=$(minikube ip)
```

Example
:
```
$ export NEXUS=$(minikube ip)
$ echo $NEXUS
192.168.64.3
$
```

#### Deploy Cassandra

Command
:
```
kubectl apply -f $MINI/cassandra.yaml && \
  kubectl wait pod cassandra-0 --for condition=ready --timeout=180s
```

Example
:
```
$ kubectl apply -f $MINI/cassandra.yaml
service/cassandra created
statefulset.apps/cassandra created
$ kubectl exec -it cassandra-0 -- nodetool status
Datacenter: datacenter1
=======================
Status=Up/Down
|/ State=Normal/Leaving/Joining/Moving
--  Address     Load       Tokens       Owns (effective)  Host ID                               Rack
UN  172.17.0.4  103.71 KiB  256          100.0%            80c0bdfa-1f5e-41aa-8a7e-f0dea7fe7ef0  rack1
$
```

#### Deploy Elasticsearch

Command
:
```
kubectl apply -f $MINI/elasticsearch.yaml && \
  kubectl wait pod elasticsearch-0 --for condition=ready --timeout=180s
```

Example
:
```
$ kubectl apply -f $MINI/elasticsearch.yaml
service/elasticsearch created
service/elasticsearch-discovery created
statefulset.apps/elasticsearch created
$ kubectl wait pod elasticsearch-0 --for condition=ready --timeout=60s
pod/elasticsearch-0 condition met
$ curl "http://$NEXUS/elasticsearch"
{
  "name" : "0LfjOb2",
  "cluster_name" : "es-cluster",
  "cluster_uuid" : "ZZF9_hFgTm2wQYYBKQ9dRg",
  "version" : {
    "number" : "6.4.3",
    "build_flavor" : "default",
    "build_type" : "tar",
    "build_hash" : "fe40335",
    "build_date" : "2018-10-30T23:17:19.084789Z",
    "build_snapshot" : false,
    "lucene_version" : "7.4.0",
    "minimum_wire_compatibility_version" : "5.6.0",
    "minimum_index_compatibility_version" : "5.0.0"
  },
  "tagline" : "You Know, for Search"
}
$
```

#### Deploy BlazeGraph

Command
:
```
kubectl apply -f $MINI/blazegraph.yaml && \
  kubectl wait pod blazegraph-0 --for condition=ready --timeout=180s
```

Example
:
```
$ kubectl apply -f $MINI/blazegraph.yaml
service/blazegraph created
statefulset.apps/blazegraph created
persistentvolumeclaim/storage-blazegraph created
ingress.extensions/blazegraph created
$ kubectl wait pod blazegraph-0 --for condition=ready --timeout=180s
pod/blazegraph-0 condition met
$ curl -s -H"Accept: application/json" "http://$NEXUS/blazegraph/namespace?describe-each-named-graph=false" | head -4
  {
    "head" : {
      "vars" : [ "subject", "predicate", "object", "context" ]
    },
$
```

### Deploy Nexus Services

Before configuring the services a configuration map must first be created that keeps track of the "public" ip address
of the minikube cluster. The following command will replace the `{NEXUS}` token in the `config.yaml` file with the
value stored in the `$NEXUS` variable set above.

Command
:
```
curl -s $MINI/config.yaml | sed "s/{NEXUS}/$NEXUS/g" | kubectl apply -f -
```

Example
:
```
$ curl -s $MINI/config.yaml | sed "s/{NEXUS}/$NEXUS/g" | kubectl apply -f -
configmap/config created
$ kubectl get configmap/config -o yaml | grep public.ip:
  public.ip: 192.168.64.4
$
```

#### Deploy Delta

Delta is the service providing the Nexus REST API.

Command
:
```
kubectl apply -f $MINI/kg.yaml && \
  kubectl wait pod kg-0 --for condition=ready --timeout=180s
```

Example
:
```
$ kubectl apply -f $MINI/kg.yaml
service/kg created
service/kg-hd created
statefulset.apps/kg created
persistentvolumeclaim/storage-kg created
ingress.extensions/kg created
ingress.extensions/kg-direct created
$ kubectl wait pod kg-0 --for condition=ready --timeout=180s
pod/kg-0 condition met
$ curl -s "http://$NEXUS/version" | jq
{
  "delta": "1.4.0",
  "storage": "1.4.0",
  "elasticsearch": "7.4.0",
  "blazegraph": "2.1.5"
}
$ curl -s "http://$NEXUS/v1/resources/org/proj" | jq # the 404 error is expected
{
  "@context": "https://bluebrain.github.io/nexus/contexts/error.json",
  "@type": "ProjectNotFound",
  "label": "org/proj",
  "reason": "Project 'org/proj' not found."
}
$
```

#### Deploy the web interface

The Nexus web application provides an interface to perform basic tasks on organizations and projects and query the
system resources through Elasticsearch and SPARQL queries.

Command
:
```
kubectl apply -f $MINI/web.yaml && \
  kubectl wait pod nexus-web-0 --for condition=ready --timeout=180s
```

Example
:
```
$ kubectl apply -f $MINI/web.yaml
service/nexus-web created
statefulset.apps/nexus-web created
ingress.extensions/nexus-web created
$ kubectl wait pod nexus-web-0 --for condition=ready --timeout=180s
pod/nexus-web-0 condition met
$
```

You can now access the web interface at `http://$NEXUS`, `$NEXUS` being the public IP of your Minikube
cluster, as seen above.

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
exception at this point being BlazeGraph which doesn't come with a clustering option). The advantages for deploying
clusters are generally higher availability, capacity and throughput at the cost of higher latency, consistency and
having to potentially deal with network instability.

The decision to go with single node deployments or clustered deployments can be revisited later on and mixed setups
(some services single node while others clustered) are also possible.

The Nexus distribution is made up of docker images which can be run on any host operating system and each of the "off
the shelf" products also offer docker as a deployment option. We would generally recommend using a container
orchestration solution like @link:[Kubernetes](https://kubernetes.io/){ open=new }, @link:[OpenShift](https://www.openshift.com/){ open=new } or
@link:[Docker Swarm](https://docs.docker.com/engine/swarm/){ open=new } as they offer good management capabilities, discovery, load
balancing and self-healing. They also accommodate changes in hardware allocations for the deployments, changes that can
occur due to evolving usage patterns, software updates etc. Currently the biggest Nexus deployment is at EPFL within
OpenShift.

### Choice of hardware

Depending on the target throughput, usage profiles and data volume the hardware specification can vary greatly; please
take a look at the @ref:[benchmarks section](../delta/benchmarks.md) to get an idea of what you should expect in terms
of throughput with various hardware configurations. When the usage profiles are unknown a couple of rules of thumb
should narrow the scope:

1.  Nexus uses a collection of data stores (@link:[Cassandra](https://cassandra.apache.org/){ open=new },
    @link:[ElasticSearch](https://www.elastic.co/elasticsearch/){ open=new }, 
    @link:[BlazeGraph](https://blazegraph.com/){ open=new }) which depend performance wise to the underlying disk 
    access, so:
    *   prefer local storage over network storage for lower latency when doing IO,
    *   prefer SSD over HDDs because random access speed is more important than sequential access,
    *   one exception is the file storage (@ref:[file resources](../delta/api/current/kg-files-api.md) which are stored as
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

### Cassandra

Nexus uses @link:[Cassandra](https://cassandra.apache.org/){ open=new } as its _primary store_ as it scales well in terms of reads with the
number of nodes in the cluster. It offers data replication out of the box, which allows the system to continue to be
available in case of node failures or network partitions.

Since this is the _primary store_ it is the most important system to be
@link:[backed up](https://docs.datastax.com/en/archived/cassandra/3.0/cassandra/operations/opsBackupRestore.html){ open=new }. All of the data
that Nexus uses in other stores can be recomputed from the one stored in Cassandra as the other stores are used as
mere indexing systems.

Please have a look at the @link:[Planning and Testing](https://docs.datastax.com/en/dse-planning/doc/){ open=new } section in the
DataStax documentation as it contains recommendations in terms of hardware and capacity.

As described in the @ref:[architecture section](../delta/architecture.md) the generally adopted
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

### ElasticSearch

Nexus uses @link:[ElasticSearch](https://www.elastic.co/elasticsearch/){ open=new } to host several _system_ indices and _user
defined_ ones. It offers sharding and replication out of the box. Deciding whether this system requires backup depends
on the tolerated time for a restore. Nexus can be instructed to rebuild all indices using the data from the _primary
store_, but being an incremental indexing process it can take longer than restoring from a backup. Since it can be
configured to host a number of replicas for each shard it can tolerate a number of node failures.

The ElasticSearch @link:[setup documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/setup.html){ open=new }
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
*   3 documents per resource (the number of documents depends on the configured @ref:[views](../delta/api/current/views/index.md)
    in the system)
*   2 additional shard replicas (replication factor of 3)

... the total required disk size would be:
```
(10KB * 1.000.000 * 3 + 2 * (10KB * 1.000.000 * 3)) * 3 = 270.000.000KB ~= 260GB
```
The resulting size represents the total disk space of the data nodes in the cluster; a 5 data node cluster with the data
volume in the example above would have to be configured with 60GB disks per node.

### BlazeGraph

Nexus uses @link:[BlazeGraph](https://blazegraph.com/){ open=new } as an RDF (triple) store to provide a advanced querying
capabilities on the hosted data. This store is treated as a specialized index on the data so as with Kafka and
ElasticSearch in case of failures, the system can be fully restored from the primary store. While the technology is
advertised to support @link:[High Availability](https://github.com/blazegraph/database/wiki/HAJournalServer){ open=new } and
@link:[Scaleout](https://github.com/blazegraph/database/wiki/ClusterGuide){ open=new } deployment configurations, we have yet to be able
to setup a deployment in this fashion.

We currently recommend deploying BlazeGraph using the prepackaged _tar.gz_ distribution available to download from
@link:[sourceforge](https://sourceforge.net/projects/bigdata/files/bigdata/2.1.4/){ open=new }.

@@@ note

We're looking at alternative technologies and possible application level (within Nexus) sharding and replicas.

@@@

The @link:[Hardware Configuration](https://github.com/blazegraph/database/wiki/Hardware_Configuration){ open=new } section in the
documentation gives a couple of hints about the requirements to operate BlazeGraph and there are additional sections
for optimizations in terms of @link:[Performance](https://github.com/blazegraph/database/wiki/PerformanceOptimization){ open=new },
@link:[IO](https://github.com/blazegraph/database/wiki/IOOptimization){ open=new } and
@link:[Query](https://github.com/blazegraph/database/wiki/QueryOptimization){ open=new }.

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
@link:[CompactJournalUtility](https://github.com/blazegraph/database/blob/master/bigdata-core/bigdata/src/java/com/bigdata/journal/CompactJournalUtility.java){ open=new }
to reduce the disk usage, but it takes quite a bit a time and requires taking the software offline during the process.
