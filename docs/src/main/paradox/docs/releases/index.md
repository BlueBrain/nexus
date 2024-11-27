@@@ index

- @ref:[v1.11 Release Notes](v1.11-release-notes.md)
- @ref:[v1.10 To v1.11 Migration](v1.10-to-v1.11-migration.md)
- @ref:[v1.10 Release Notes](v1.10-release-notes.md)
- @ref:[v1.9 To v1.10 Migration](v1.9-to-v1.10-migration.md)
- @ref:[v1.9 Release Notes](v1.9-release-notes.md)
- @ref:[v1.8 To v1.9 Migration](v1.8-to-v1.9-migration.md)
- @ref:[v1.8 Release Notes](v1.8-release-notes.md)
- @ref:[v1.7 To v1.8 Migration](v1.7-to-v1.8-migration.md)
- @ref:[v1.7 Release Notes](v1.7-release-notes.md)
- @ref:[v1.6 To v1.7 Migration](v1.6-to-v1.7-migration.md)
- @ref:[v1.6 Release Notes](v1.6-release-notes.md)
- @ref:[v1.5 To v1.6 Migration](v1.5-to-v1.6-migration.md)
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

- The Remote storage server implementation has been removed.
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

## 1.7.2 (16.06.2022)

This is a patch release that addresses a series of Nexus Fusion issues:

### What's Changed

- Prevent search table header controls from overflowing onto new line by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/987
- 2709 redesign resource view by @nicwells in https://github.com/BlueBrain/nexus-web/pull/986
- 2932 Prevent Resource side drawer from closing when it shouldn't by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/988
- 2807 admin layout tab update by @smitfire in https://github.com/BlueBrain/nexus-web/pull/985
- 1315 open links by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/989
- 2890 align styling in fusion by @nicwells in https://github.com/BlueBrain/nexus-web/pull/991
- Switch logic - multi-value field has and, single-valued not by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/993
- 2611 enable jump to studio from search bar by @nicwells in https://github.com/BlueBrain/nexus-web/pull/992
- 2943 reinstate create resource in admin by @nicwells in https://github.com/BlueBrain/nexus-web/pull/994
- 2944 custom views to be listed in query tab by @nicwells in https://github.com/BlueBrain/nexus-web/pull/995
- Changing filter value triggers on finish automatically vs apply button by @smitfire
  in https://github.com/BlueBrain/nexus-web/pull/990
- Fixed filter bug check by @smitfire in https://github.com/BlueBrain/nexus-web/pull/996
- Remove fuzziness from search by @nicwells in https://github.com/BlueBrain/nexus-web/pull/998
- Use Resource end point for studio listing by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/999
- PDF Viewer not loading fix by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1000
- Search filter bug condition by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1003
- 2941 styling improvements fixes and improvements by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1002
- Support removing tags from resources by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1001
- Disable data cart download button when no perms by @nicwells in https://github.com/BlueBrain/nexus-web/pull/997
- Persist open/close state of plugins by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1004
- 2922 support dates by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1005
- Handle objects in search by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1007
- remove double quotes from display by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1008
- 1673 better error when try recreate deprecated org or project by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1009
- Support composite views in data tables by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1006
- Fix array values by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1010
- Fix creation of resources list from "copy resource list" shortcut link by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1012
- 2644 allow user to select search config by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1011
- Add redirect params to login call back by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1014
- 2986 fix styles by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1015
- Always keep tooltip visible in viewport by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1017
- 2902 move sidebar to header by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1016
- added numerics and conditions to call numerics component to search by @smitfire
  in https://github.com/BlueBrain/nexus-web/pull/1013
- Fix behaviour of copy button by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1019
- 2835 Search bar text and searching for things with same value as project or studio by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1020
- More specific css rules and fix at same time by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1021
- Graph Analytics by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1022
- 2082 admin plugin link support navigation versioned identifiers by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1023
- 2999 scale large pdfs down to size and support zoom/pan by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1026
- Fix to allow user to scroll all the way to bottom of gallery drawer by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1027
- Only show graph analytics tab when plugin enabled in Delta by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1028
- 3020 consistent datetime throughout app by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1029
- Fix style by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1032
- Move hook call to top-level of function component by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1033
- Add edges to GraphAnalytics by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1031
- Copy notification for button dropdowns by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1034
- Histogram and stats by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1030
- graph analytics info blurbs by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1037
- Add AntD Statistics component to display Statistics by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1038
- Add edge arrow for target to show directionality by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1040
- 3084 app crash when adding to cart from data table by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1039
- 3094 3095 search numeric column chart type and style by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1041
- Minor fixes for Graph Analytics by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1042
- Fix number formatting for GraphAnalytics Panel by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1043
- Clear resource list filter by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1044
- fix row count, row selection, select-row width for pagination by @dhaneshnm
  in https://github.com/BlueBrain/nexus-web/pull/1045
- Set padding on percentage, to accoint for view resizing by @dhaneshnm
  in https://github.com/BlueBrain/nexus-web/pull/1046
- Remove bidirectional arrows by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1047
- 3113 search reset bug fix by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1048
- Load from ENV the name of service account realm to be hidden for Login by @bogdanromanx
  in https://github.com/BlueBrain/nexus-web/pull/1050
- 3106 numeric missing bug by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1049
- Clear Search text by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1051
- Fix bug where deselecting filter value does not remove it by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1054
- Handle case where there are multiple label values by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1052
- Fixed data cart search bug by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1055
- Set average value and precesion value by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1065
- Statistics tab by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1066
- Correct the behaviour of 'missing' filter by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1068
- set Infinity as the default limit for slider by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1067
- 2974 handle missing metadata in plugins by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1064
- Refactor edit table by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1069
- Sorting based on filter by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1070
- Add an additional spinner for data results in table. by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1071
- 3114 scroll bug by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1075
- Fix when missing permissions tooltip is displayed by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1077
- 3174 3176 small studio UI fixes by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1076
- Correct url with destination by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1073
- Clicking icon in search config opens select by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1081
- Provide a friendlier error message when unable to access authentication provider by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1079
- Fix studio scroll issue by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1074
- 3184 honour filterable flag by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1080
- Project query aggregate in list too by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1083
- 3039 jira discussions by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1085
- Jira - switch to using self url for project and resource, better error handling and only show plugin when enabled by
  @nicwells in https://github.com/BlueBrain/nexus-web/pull/1095
- Preview download plugins - keep original filenames and fix error by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1107
- 3190 copy url 2 by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1108
- 1.8.0 m3 by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1105
- 3191 deprecated false included in query by @smitfire in https://github.com/BlueBrain/nexus-web/pull/1109
- 1.7.1 fix by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1110
- set a default view for workspaces by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1112
- Check for case where Jira oauth token rejected by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1111
- Fix plugins studio issues by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1113
- Save any plugin config on initial studio creation by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1115
- Update menu after updating dashboard by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1116
- Rename Preview button and Disable Save by @dhaneshnm in https://github.com/BlueBrain/nexus-web/pull/1117
- Fix jira error checking condition by @nicwells in https://github.com/BlueBrain/nexus-web/pull/1118
- Hide the JIRA plugin if user is not authenticated with a configurable realm by @nicwells
  in https://github.com/BlueBrain/nexus-web/pull/1119

**Full Changelog**:
Fusion: [v1.7.1...v1.7.2](https://github.com/BlueBrain/nexus-web/compare/v1.7.1...v1.7.2)

The corresponding Fusion release notes can be found [here](https://github.com/BlueBrain/nexus-web/releases/tag/v1.7.2).

## 1.7.1 (01.06.2022)

This is a bug-fix release that addresses a series of Delta and Fusion issues:

- Fetch original payload is not retrieving null values #3112
- Unable to upload empty files using the RemoteDiskStorage #2921
- Improve error message on remote storage errors #3254
- ElasticSearchView resourceTag config is not taken in consideration for synchronous indexing #3266
- New layout for studios
- Ability to control plugins at studio level
- Style improvements of search filter panel
- We have added a [script](https://github.com/BlueBrain/nexus-web/blob/main/migrations/README.md) to migrate studios
  using pre-v1.7.0 format to v1.7.1+

### Fixes

A [series](https://github.com/BlueBrain/nexus/issues?q=is%3Aissue+is%3Aclosed+closed%3A2021-03-08..2022-06-01+label%3Abug)
of bugs have been fixed.

The corresponding Fusion release notes can be found [here](https://github.com/BlueBrain/nexus-web/releases/tag/v1.7.1).
The corresponding Delta release notes can be found [here](https://github.com/BlueBrain/nexus/releases/tag/v1.7.1).

**Full Changelogs**:
Delta: [v1.7.0...v1.7.1](https://github.com/BlueBrain/nexus/compare/v1.7.0...v1.7.1)
Fusion: [v1.7.0...v1.7.1](https://github.com/BlueBrain/nexus-web/compare/v1.7.0...v1.7.1)

## 1.7.0 (14.03.2022)

### Breaking changes

- Removal of the Nexus cli
- Removal of the docker based client

### Deprecations

In the upcoming version, the support of Cassandra as a primary store for Nexus Delta will be removed in favour of
PostgreSQL to focus development efforts on features rather than supporting multiple databases.
For very large deployments there are commercial options that are wire compatible to PostgreSQL.

Tools will be provided to enable migration from Cassandra to PostgreSQL for existing Delta deployments.

### New features / enhancements

- New features and better user experience for search in Nexus Fusion
- Introduced a new extensible model to define Elasticsearch views based on pipes
- Allow the use of an external configuration file for Delta
- Allow deleting tags on resources
- Allow tagging deprecated storages and views
- Refactor the `graph-analytics` plugin to make indexing faster
- Add a group identifier in Elasticsearch projections of composite views

A detailed list of changes included in the release can be found in the @ref:[release notes](v1.7-release-notes.md).

## v1.6.1 (29.10.2021)

This release contains bugfixes and minor improvements:

- Graph-analytics returns edges for non-existing nodes @link:[#2871](https://github.com/BlueBrain/nexus/issues/2871)
- Graph analytics is trying to resolve every link to all types of resources
  @link:[#2852](https://github.com/BlueBrain/nexus/issues/2852)
- Composite key values cache is not distributed across nodes
  @link:[#2909](https://github.com/BlueBrain/nexus/issues/2909)
- Shortcut Acl permission check (project -> org -> root) when address matches early
  @link:[#2916](https://github.com/BlueBrain/nexus/issues/2916)
- Resource-view opens up as a side panel @link:[#2617](https://github.com/BlueBrain/nexus/issues/2617)
- User can see all data, when the search query is empty @link:[#2875](https://github.com/BlueBrain/nexus/issues/2875)
- A loading spinner shows up when there is a delay in fetching search results
  @link:[#2880](https://github.com/BlueBrain/nexus/issues/2880)
- Label 'none of' in filter was previously mis labelled as 'any of'
  @link:[#2872](https://github.com/BlueBrain/nexus/issues/2872)
- The behaviour of 'none of' filter has been fixed to avoid confusion with other filters
  @link:[#2898](https://github.com/BlueBrain/nexus/issues/2898)
- Preview plugin big fix to prevent it from crashing for certain file paths
  @link:[#2884](https://github.com/BlueBrain/nexus/issues/2884)
- Search bar matches query content @link:[#2874](https://github.com/BlueBrain/nexus/issues/2874)

**Full Changelogs**:

- Delta: @link:[v1.6.0...v1.6.1](https://github.com/BlueBrain/nexus/compare/v1.6.0...v1.6.1)
- Fusion: @link:[v1.6.0...v1.6.1](https://github.com/BlueBrain/nexus-web/compare/v1.6.0...v1.6.1)

## v1.6.0 (13.10.2021)

### Deprecations

- Nexus client
- Indexing data in other systems
- Remote storages

### New features / enhancements

- Introduced a plugin to search among different projects
- Introduced PDF, CSV, TSV and Youtube Viewer Fusion Plugins
- Add basic authentication to access a secured Elasticsearch cluster
- Handle user-defined queries to Blazegraph with a dedicated client
- Introduced a plugin to analyze properties and relationships of resources within a project
- Synchronous indexing
- Listing of resources outside the project scope
- The RDF parser to validate resources is now configurable
- Automatic project provisioning
- Introduced quotas on projects
- Project deletion (on demand and automatic)
- Tagging resources after deprecation
- View passivation

A detailed list of changes included in the release can be found in the @ref:[release notes](v1.6-release-notes.md).

## Older releases

The release notes of older versions are available @link:[here](https://bluebrainnexus.io/v1.6.x/docs/releases/index.html).