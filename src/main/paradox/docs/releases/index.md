@@@ index

* [v1.2 Release Notes](v1.2-release-notes.md)
* [v1.1 Release Notes](v1.1-release-notes.md)
* [v1.0 To v1.1 Migration](v1.0-to-v1.1-migration.md)
* [v1.0 Release Notes](v1.0-release-notes.md)

@@@

# Releases

This section of the documentation lists the significant BlueBrain Nexus releases across all services and web applications.

The latest stable release is **v1.2.0** released on **04.10.2019**.

## v1.2.1 (07.10.2019)

This is a bugfix release, addressing two specific issues:

* Fix `FileAttributesUpdated` event deserialization which was causing indexing problems for situations where the
  deployment included a remote storage service to handle files.
* Removed `kamon-akka-remote` dependency which was causing problems in clustered deployments due to binary compatibility
  issues.

## v1.2.0 (04.10.2019)

The release adds two major features:
 
- endpoint to fetch the original payload of a resource.
- ability to retrieve multiple resources in one request as an archive. 
 
The API is backwards compatible with v1.1.

Summary of the significant changes:

Storage service related updates:

* Updated async computation of to return not only the digest information but all the attributes (bytes, digest, mediaType and location).

KG updates:

* Added @ref:[archives resources](../api/current/kg/kg-archives-api.md).
* Added [/source](../api/current/kg/kg-resources-api.html#fetch-a-resource-original-payload) sub-resource.
* [Fixed issue](https://github.com/BlueBrain/nexus/issues/750) with resource retrieval when linked context changes.
* Updated `DigestViewCoordinator` to `AttributesViewCoordinator`. This async process now updates all the FileAttributes.

Dependency updates:

* SHACL validator, akka-http, cats, cats-effects amongst others

## v1.1.2 (24.09.2019)

The release addresses bug fixing and is backwards compatible with v1.0 in terms of API. If you're upgrading from v1.0 please visit the
@ref:[migration instructions](v1.0-to-v1.1-migration.md).

Summary of the significant changes:

Storage service related updates:

* Added async computation of the file digest.
* Before an action gets executed against the storage, checks that the resource created is valid (is not deprecated, has the correct revision, etc...)

KG Fixes:

* When project is not present in the cache but it is present in the underlying admin service, adds it directly to the cache (before the cache was populated from the SSE, which can be very slow).
* `ProjectViewCoordinator` and `DigestViewCoordinator` actors now create child actors (better management of actors lifecycle).
* Prevented from creating unnecessary indices/namespaces.

Fixed library dependency issues:

* Corrected Iri to Akka.Uri conversion
* Corrected pct encoding (Iri.asString and Iri.asUri)
* Bumped akka and kamon dependencies, amongst others

## v1.1.1 (24.07.2019)

The release addresses bug fixing and is backwards compatible with v1.0 in terms of API. If you're upgrading from v1.0 please visit the
@ref:[migration instructions](v1.0-to-v1.1-migration.md).

Summary of the significant changes:

* Migration script correctly updates views with the expected defaults
* Migration script jumps over event deserialization errors
* Metric tag value fix for elasticsearch indexer
* Kamon disabled by default
* Kamon agent is loaded as a JVM argument
* Updated library dependencies

## v1.1 (19.07.2019)

The release is backwards compatible with v1.0 in terms of API. If you're upgrading from v1.0 please visit the
@ref:[migration instructions](v1.0-to-v1.1-migration.md).

Summary of the significant changes:

*   Exposed the service event logs over a stable API via [Server Sent Events](https://www.w3.org/TR/eventsource/).
*   Introduced configurable storage backends for files with local, remote and S3 implementations.
*   ElasticSearchView | AggregateElasticSearchView have been promoted to stable.
*   Introduced a new SPARQL view, AggregateSparqlView, that dispatches SPARQL queries to the appropriate namespaces and
    aggregates the results.
*   ElasticSearchView and SparqlView support additional configuration options: resourceSchemas, resourceTypes, resourceTag, includeDeprecated, includeMetadata.
*   API improvements:
    *   Support for additional filtering criteria when listing resources via query params: rev, deprecated, createdBy, updatedBy.
    *   The organization and project segments when exercising the API now accept their unique ids (UUID).
    *   Content negotiation for resources, supporting: dot, ntriples, json-ld expanded and compacted formats.
    *   Ability to resolve resource ids via configured project resolvers.
    *   Pagination of resources over 10,000 using the ''_next'' link in the listing response.
    *   Resource metadata includes ''_incoming'' and ''_outgoing'' links and the API now includes their respective endpoints.
    *   View indexing progress as a ''statistics'' sub-resource of each view.
*   Nexus Web improvements:
    *   Better OpenIdConnect integration, ability to authenticate to multiple configured realms.
    *   Ability to discriminate between Nexus specific resources and user managed resources.
    *   Display the current ACLs and their source for the logged in user.
    *   Ability to query user defined views.
    *   Display the indexing progress for the active view.
*   Exposed view indexing progress metrics for Prometheus.
*   Bumped ElasticSearch compatibility to 7.x.

A detailed list of changes included in the release can be found in the @ref:[release notes](v1.1-release-notes.md).

## v1.0 (25.01.2019)

This is the first major release of Blue Brain Nexus after almost two years of development.

Also referred to as "_Nexus V1_", this initial release is our first big milestone in our quest to build a Knowledge
Graph platform uniquely combining flexible graph database, powerful search engine and scalable data store to enable:

*   Easy unification and integration of fragmented and disparate data from heterogeneous domains to break data and
    metadata silos
*   Better data governance with the ability to specify and enforce organization’s best practices for data collection,
    storage and description through high quality metadata
*   Data lineage and provenance recording and description
*   FAIR (Findable, Accessible, Interoperable, Re-usable) data and metadata management

A detailed list of changes included in the release can be found in the @ref:[release notes](v1.0-release-notes.md).
