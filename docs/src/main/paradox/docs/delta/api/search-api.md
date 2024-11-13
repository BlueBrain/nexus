# Global search

Nexus provides global search functionality across all projects through the
@link:[search plugin](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/search/src){ open=new }.

@@@ warning
The search plugin is experimental and its functionality and API can change without notice.
@@@

The search plugin relies on @ref:[composite views](./views/composite-view-api.md):

* An underlying composite view is created for every project in the Nexus deployment
* Those views are identical and index data from their enclosing project in a single Elasticsearch projection.
* The query endpoints filter the Elasticsearch projections from the underlying composite views and only returns results from
  indices to which user has access to, i.e. has `views/query` permission.

For instructions on how to configure global search in Nexus and how it works please visit the
@ref:[Search configuration](../../running-nexus/search-configuration.md) page.

@@@ note { .tip title="Api Mapping" }

The underlying composite views can queried to monitor their indexing progress, to query their intermediate namespaces, ...

An API mapping `search` has been defined to make it easier:

Example:
```
GET /v1/views/myorg/myproject/search/offset
```
Will allow to get the indexing progress for search for the `myorg/myproject` 

@@@

## Query

```
POST /v1/search/query
{payload}
```
... where `{payload}` is a 
@link:[Elasticsearch query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html){ open=new }
and the response is forwarded from the underlying Elasticsearch indices.

## Query a suite

Nexus Delta allows to configure multiple search suites under @link:[`plugins.search.suites`](https://github.com/BlueBrain/nexus/blob/master/delta/plugins/search/src/main/resources/search.conf). Each suite is composed of one or more projects.
When querying using a suite, the query is only performed on the underlying Elasticsearch indices of the projects in the suite.

```
POST /v1/search/query/suite/{suiteName}?addProject={project}
{payload}
```
... where:

* `{suiteName}` is the name of the suite
* `{project}`: Project - can be used to extend the scope of the suite by providing other projects under the format `org/project`. This parameter can appear
  multiple times, expanding further the scope of the search.
* `{payload}` is a @link:[Elasticsearch query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html){ open=new }
and the response is forwarded from the underlying Elasticsearch indices.

## Configuration

```
GET /v1/search/config
```

This endpoint returns the search configuration. The contents of `plugins.search.fields` config file is the payload of
this response.

## Fetch a suite

```
GET /v1/search/suite/{suiteName}
```
... where `{suiteName}` is the name of the suite.

Request
:   @@snip [fetch-suite.sh](assets/search/fetch-suite.sh)

Response
:   @@snip [suite-fetched.json](assets/search/suite-fetched.json)
