# Changelog

List of changes from v1.0 to v1.1

## API

- Added support for Server Sent Events
- Added support for Multiple Storage Backends (DiskStorage, RemoteDiskStorage, S3Storage)
- Extended ElasticSearchView configuration flags to include: `includeDeprecated`, `resourceTypes`
- Extended SparqlView configuration flags to include: `resourceSchemas`, `resourceTag`, `includeDeprecated`, `includeMetadata`, `resourceTypes`
- Added `AggregateSparqlView` view
- Added statistics endpoint to views in order to obtain indexing progress metrics
- Extended the listings' filtering capabilities, being able to filter by: `rev`, `deprecated`, `type`, `createdBy`, `updatedBy`
- Allowed to call the API using organization and project labels and UUIDs indistinguishably
- Supported several representations for resources using HTTP content negotiation (Accept header). Supported: `text/vnd.graphviz`, `application/ntriples`, `application/ld+json` 
- Supported several Json-LD representations for resources through a `format` query parameter. Supported: `compacted`, `expanded`
- Added resolution endpoint in order to resolve resources using a series of resolvers
- Supported listings over 10.000 resources using the link on the `next` field from the response payload
- [Bug fixing](https://github.com/BlueBrain/nexus/issues?q=is%3Aissue+is%3Aclosed+milestone%3AV1.1+label%3Abug+label%3Aservices)

