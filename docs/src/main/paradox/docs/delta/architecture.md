# Architecture

Blue Brain Nexus is a collection of software components that address various organizational needs relating to data
storage, management, analysis and consumption. It was designed to support the data-driven science iterative cycle at
Blue Brain but its genericity allows for its use in arbitrary contexts.

This document focuses on the characteristics of the Nexus Delta and its design choices.

## Components

Nexus Delta is a low latency, scalable and secure service that realizes a range of functions to support data management
and knowledge graph lifecycles. It is a central piece of the Blue Brain Nexus ecosystem of software components as it
offers a set of foundational capabilities to the other components. It uses [Apache Cassandra] as a primary store
(source of truth for all the information in the system), [ElasticSearch] for full text search and [BlazeGraph] for graph
based data access.

An overview of the Blue Brain Nexus components is presented in the figure below:

@@@ div { .half .center }

![Components](assets/architecture-components.png)

@@@

Nexus Fusion is a web interface that helps scientists with their day-to-day data-driven activities but also facilitates
the system administrative tasks. It uses the Nexus JavaScript SDK that provides as set of primitives for building
web applications for Nexus Delta.

Nexus CLI is a command line interface for scripting and automating the system administrative tasks. It also provides
the ability to execute off process data projections to stores that are not directly supported by Nexus Delta.

Nexus Forge is a domain-agnostic, generic and extensible Python framework that enables non-expert users to create and
manage knowledge graphs using the Python programming language.

## Clustering

One of the more important design goals for the system was to be able scale in order to support arbitrary increases in
usage and data volume. Nexus Delta can be configured to run as single node or in a cluster configuration where the load
on the system is distributed to all members.

[Akka Cluster] was chosen for a decentralized, fault-tolerant, peer-to-peer based cluster membership. It uses the
[Gossip Protocol] to randomly spread the cluster state. Nodes in the cluster communicate over TCP using [Akka Remoting]
for coordination and distribution of load.

@@@ div { .half .center }

![Clustering](assets/architecture-clustering.png)

@@@

[Apache Cassandra] and [ElasticSearch] were chosen for their horizontal scaling characteristics and for favouring
availability over globally strong consistency.

[BlazeGraph] was initially chosen to handle graph access patterns, but it is currently the only part of the system that
cannot be scaled horizontally. We're currently looking for open source alternatives that offer clustering out of the box
or solutions that would coordinate multiple BlazeGraph nodes. 

## Anatomy

Nexus Delta was built following the Command Query Responsibility Segregation ([CQRS]) pattern where there's a clear
separation between the read and write models. Intent to change the application state is represented by commands that
are validated for access and consistency before being evaluated. Successful evaluations of commands emit events that are
persisted to global event log.

Asynchronous processes (projections) replay the event log and process the information for efficient consumption. The
information in the recorded events is transformed into documents (in the case of ElasticSearch) and named graphs (in
the case of BlazeGraph) and persisted in the respective stores. The projections persist their progress such that
they can be resumed in case of a crash.

Sources of events for projections are both the primary store and other (remote) Nexus Delta deployments. This allows
for data aggregation when building indices.

Native interfaces are offered as part of the read (query) model for querying ElasticSearch and BlazeGraph.

@@@ div { .center }

![Anatomy](assets/architecture-anatomy.png)

@@@

Asynchronous indexing (projections) and the separation between reads and writes have some interesting consequences:

*   the system is eventually consistent and does not require a healing mechanism for handling synchronization errors
*   the primary store acts as a bulkhead in case of arbitrary data ingestion spikes
*   the primary store and the stores used for indices can be independently sized; indexing speed is allowed to vary
    based on the performance of each store
*   the system continues to function with partial degradation instead of becoming unavailable if a store suffers
    downtime

[Apache Cassandra] is used as an eventsourced primary store and represents the source of truth for all the information
in the system. Updates are not performed in place, state changes are appended to the event log. The state of the system
is derived from the sequence of events in the log.

The global event log is partitioned such that there's no need to replay the entire log. Subsets can be replayed, like
for example when reconstructing the current state of a single resource. 

@@@ div { .three-quarters .center }

![Event Log](assets/architecture-event-log.png)

@@@

## Resource Orientation

Nexus Delta is built following the REpresentational State Transfer ([REST]) architectural style where its functions are
consumed via access and manipulation of resources. All information in the system (system configuration or user data) is
represented as resources. The @ref:[API Reference] describes all supported resource types, the addressing scheme and
available operations.

The subset of events that correspond to single resource represent the resource lifecycle as depicted in the figure
below. A resource lifecycle is a series of state transitions, each generating a unique revision.

@@@ div { .center }

![Resource Lifecycle](assets/architecture-resource-lifecycle.png)

@@@

User data is represented as sub-resources to projects which in turn are sub-resources of organizations. Organization
and project resources provide logical grouping and isolation allowing for variation in configuration and access control
policies.

@@@ div { .three-quarters .center }

![Scoping](assets/architecture-scoping.png)

@@@

Resource identification is based on HTTP Internationalized Resource Identifiers ([IRI]s) and uniqueness is guaranteed
within the scope of a project. This allows the system to be used in a multi-tenant configuration but at the same time
it implies that project and organization identifiers are part of a resource addressing scheme.

In order to avoid limitations in URL lengths and for convenience, resource identifiers can be aliased and compacted
([CURIE]) using project level configurations.

## Authentication and Authorization

TBD

[Apache Cassandra]: https://cassandra.apache.org/
[ElasticSearch]: https://www.elastic.co/elasticsearch/
[BlazeGraph]: https://blazegraph.com/
[Akka Cluster]: https://doc.akka.io/docs/akka/current/typed/cluster-concepts.html
[Gossip Protocol]: https://en.wikipedia.org/wiki/Gossip_protocol
[Akka Remoting]: https://doc.akka.io/docs/akka/current/remoting-artery.html
[CQRS]: https://martinfowler.com/bliki/CQRS.html
[REST]: https://en.wikipedia.org/wiki/Representational_state_transfer
[API Reference]: ./api/current/index.md
[IRI]: https://tools.ietf.org/html/rfc3987
[CURIE]: https://www.w3.org/TR/curie/