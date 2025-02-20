@@@ index

- @ref:[v1.11 Release Notes](v1.11-release-notes.md)
- @ref:[v1.10 To v1.11 Migration](v1.10-to-v1.11-migration.md)
- @ref:[v1.10 Release Notes](v1.10-release-notes.md)
- @ref:[v1.9 To v1.10 Migration](v1.9-to-v1.10-migration.md)
- @ref:[v1.9 Release Notes](v1.9-release-notes.md)
- @ref:[v1.8 To v1.9 Migration](v1.8-to-v1.9-migration.md)
- @ref:[v1.8 Release Notes](v1.8-release-notes.md)
- @ref:[v1.7 To v1.8 Migration](v1.7-to-v1.8-migration.md)
- @ref:[Older releases](older-releases.md)

@@@

# Releases

This section of the documentation lists the significant BlueBrain Nexus releases across all services and web
applications.

TODO: update release date
The latest stable release is **v1.10.0** released on **17.09.2024**.

## 1.11.0

### Breaking changes

@@@ note { .warning }

The items listed below are changes that have been made in this release that break compatibility with previous releases.

- The Remote storage implementation has been removed.
- The automatic provisioning of projects has been removed.
- The Jira integration plugin has been removed.
@@@

### New features/enhancements

- @ref:[Conditional requests](../delta/api/conditional-requests.md)
- @ref:[Passivation](../delta/api/views/index.md#passivation)

## 1.10.0

### Breaking changes

@@@ note { .warning }

The items listed below are changes that have been made in this release that break compatibility with previous releases.

- The S3 support for files has been completely rewritten
- The default Elasticsearch views now uses a new mapping and settings which improves the ability to search for resources
  using the listing endpoints.
- Resolvers/storages/views can't be tagged anymore
- The global SSE endpoint, the SSE endpoints for realms/acls/organizations and the SSE endpoint
  to fetch indexing errors have been removed
- Fetch organizations and projects by their uuid is now removed

@@@

### Deprecations

@@@ note { .warning }

The items listed below are announcement of future deprecation, these features will be removed in the next release.

- Remote storage support
- Jira Plugin

@@@

### New features / enhancements

- @ref:[S3 support has been rewritten with new features](../delta/api/files-api.md)
- Ability to @ref[enforce usage of schema at Project level](../delta/api/projects-api.md)
- Ability to retrieve the annotated original payload for a @ref:[Schema](../delta/api/schemas-api.md#fetch-original-payload) and a @ref:[Resolver](../delta/api/resolvers-api.md#fetch-original-resource-payload-using-resolvers)
- Ability to add custom metadata upon @ref:[creating](../delta/api/files-api.md#create-using-post) and @ref[updating](../delta/api/files-api.md#update) Files
- Ability to @ref:[fetch a search suite](../delta/api/search-api.md#fetch-a-suite)
- Ability to check if a project has been @ref:[correctly provisioned](../delta/api/supervision-api.md#projects-health) and potentially @ref:[heal](../delta/api/supervision-api.md#project-healing) this process 
- The @ref:[Projects](../delta/api/projects-api.md#undeprecate) and the different types of resources (
  @ref:[Storages](../delta/api/storages-api.md#undeprecate), 
  @ref:[ElasticSearch Views](../delta/api/views/elasticsearch-view-api.md#undeprecate), @ref:[Sparql Views](../delta/api/views/sparql-view-api.md#undeprecate), @ref:[Composite Views](../delta/api/views/composite-view-api.md#undeprecate), 
  @ref:[Schemas](../delta/api/schemas-api.md#undeprecate)) can now be undeprecated
- @ref:[Custom metadata can now be added to files](../delta/api/files-api.md#create-using-post)
- @ref:[Creating point-in-time for Elasticsearch queries](../delta/api/views/elasticsearch-view-api.md#create-a-point-in-time)

A detailed list of changes included in the release can be found in the @ref:[release notes](v1.10-release-notes.md).

## 1.9.1 (13.02.2024)

- Fix computing diff on large resources during updates

## 1.9.0 (18.12.2023)

### Breaking changes

- @ref:[Resource payloads can't contain fields starting by `_` anymore](../delta/api/resources-api.md)
- The endpoint for fetching indexing errors as SSEs changed
- @ref:[Credentials for storages can now only be defined at the configuration level](../releases/v1.9-release-notes.md#remote-storages)
- @ref:[Encoding of self, incoming and outgoing links](../releases/v1.9-release-notes.md#self-incoming-and-outgoing-links)
- @ref:[Remove support for Tarball archives](../releases/v1.9-release-notes.md#remove-support-for-tarball-archives)

### Deprecations

- Fetching resources using organization and project uuids
- Tagging operations for resolvers/storages/views
- Indexing projects within views
- Removing generic endpoints to create/update/deprecate resources
- Global SSE endpoint
- SSE endpoints for realms/acls/organizations
- SSE endpoint to fetch indexing errors
- Injecting org/project uuids in SSEs related to resources

### New features / enhancements

- @ref:[Aggregations of resources by `@type` and `project`](../delta/api/resources-api.md#aggregations)
- @ref:[Files can be added to an archive using `_self`](../delta/api/archives-api.md#payload)
- @ref:[Indexing errors can now be listed and filtered](../delta/api/views/index.md#listing-indexing-failures)
- @ref:[Multi fetch operation allows to get multiple resources in a single call](../delta/api/multi-fetch.md)
- @ref:[Resources trial and resource generation](../delta/api/trial.md#resource-generation)
- @ref:[Schema changes](../releases/v1.9-release-notes.md#schema-changes)
- @ref:[Tagging at creation/updates](../delta/api/resources-api.md)
- @ref:[Undeprecating resources, files, projects, organizations](../releases/v1.9-release-notes.md)
- @ref:[Improving performance of composite view](../releases/v1.9-release-notes.md#composite-views)
- @ref:[Id resolution](../delta/api/id-resolution.md)
- **Global User Interface Improvements:** Multi-type filter selection in "My Data" section, improved header menu behavior, and enhanced UI element uniformity.
- **Data Explorer Enhancements:** New filtering and navigation features, fullscreen mode for Graph Flow, advanced mode beta, and improved navigation with back and forward buttons.
- **Resource Management and Display:** Display of resources missing paths, clearer resource counts, introduction of Data Cart logic, and improved ACL checks.
- **Code Editor and Studio Enhancements:** New URL copying feature in Code Editor, typo corrections and layout adjustments in the studio interface, and enhanced resource management tools.
- **Operational Improvements:** Advanced search and filter capabilities, bulk request handling, new resource tagging features, and enhanced error handling mechanisms.
- **Hotfixes and Minor Updates:** Addressing issues such as navigation speed, URL encoding in downloads, and UI glitches.

A detailed list of changes included in the release can be found in the @ref:[release notes](v1.9-release-notes.md).

## 1.8.0 (14.06.2023)

### Breaking changes

- The support for Cassandra has been removed and PostgreSQL is now the only supported primary store for Nexus Delta

### Deprecations

In the upcoming version, the support of the tar format to download archives will be removed, only the zip format will remain.

### New features / enhancements

- New sourcing and streaming library
- New clustering deployment
- Better reporting of indexing errors for views
- Name and description for resolvers, storages and views
- Only trigger reindexing when indexing is impacted
- Project deletion has been rewritten
- @ref:[A refresh operation is now available for resources](../delta/api/resources-api.md#refresh)
- @ref:[A validate operation is now available for resources](../delta/api/trial.md#validate)
- Archives can now be downloaded as a zip

A detailed list of changes included in the release can be found in the @ref:[release notes](v1.8-release-notes.md).

## Older releases

The release notes of older versions are available @link:[here](https://bluebrainnexus.io/v1.8.x/docs/releases/index.html).