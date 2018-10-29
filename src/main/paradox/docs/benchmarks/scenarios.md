# Scenarios

All the scenarios are based on a provenance pattern which spans multiple entities.

## [Upload scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/CreateSimulation.scala)

This scenario creates a specified number o resources in a project using a prepared provenance template.
The number of threads used to perform the upload can be customized.

## [Fetch scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/FetchSimulation.scala)

This scenario simulates the fetching of resources from Nexus KG. For each schema in the system, the simulation performs listing
and fetches a random resource for the schema. For a defined ratio of resources an update will be applied.

## [Update scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/UpdateSimulation.scala)

This scenario applies a specified number of revision to resources.

## [Fetch by revision scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/GetByRevSimulation.scala)

This scenario tries to fetch a random revision of a resource.

## [Tagging scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/TagSimulation.scala)

This scenario applies a specified number of tags to resources

## [Fetch by tag scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/GetByTagSimulation.scala)

This scenario fetches resources by a random tag.

## [ElasticSearch search scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/ElasticSearchSimulation.scala)

This scenario performs queries against the default ElasticSearch view for the project.
The list of the queries can be found [here](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/resources/es-queries.json).

## [Blazegraph search scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/BlazegraphSimulation.scala)

This scenario performs SPARQL queries traversing the provenance graph against the default SPARQL view.
The queries used can be found [here](https://github.com/BlueBrain/nexus-tests/tree/master/src/it/resources).

## [Add attachments scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/AddAttachmentSimulation.scala)

This scenario uploads multiple attachments to resources.

## [Fetch attachments scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/FetchAttachmentSimulation.scala)

This scenario fetches attachments from resources.

## [Mixed scenario](https://github.com/BlueBrain/nexus-tests/blob/master/src/it/scala/ch/epfl/bluebrain/nexus/perf/FetchAttachmentSimulation.scala/FullSimulation.scala)

This simulation performs multiple types of requests: fetching, updating, fetching by revision, ElasticSearch and Blazegraph search.
Percentage of each type of the requests can be configured.
