# Global search

Nexus provides global search functionality across all projects through the
@link:[search plugin](https://github.com/BlueBrain/nexus/tree/$git.branch$/delta/plugins/search/src){ open=new }.

@@@ warning
The search plugin is experimental and its functionality and API can change without notice.
@@@

For instructions on how to configure global search in Nexus and how it works please visit the
@ref:[Search configuration](../../getting-started/running-nexus/search-configuration.md) page.

## Query

```
POST /v1/search/query
{payload}
```
... where `{payload}` is a 
@link:[Elasticsearch query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html){ open=new }
and the response is forwarded from the underlying Elasticsearch indices.

The endpoint filters the Elasticsearch projections from the underlying composite views and only returns results from
indices to which user has access to, i.e. has `views/query` permission.

## Query a suite

Nexus Delta allows to configure multiple search suites under @link:[`plugins.search.suites`](https://github.com/BlueBrain/nexus/blob/master/delta/plugins/search/src/main/resources/search.conf). Each suite is composed of one or more projects.
When querying using a suite, the query is only performed on the underlying Elasticsearch indices of the projects in the suite.

```
POST /v1/search/query/suite/{suiteName}
{payload}
```
... where:

* `{suiteName}` is the name of the suite
* `{payload}` is a @link:[Elasticsearch query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html){ open=new }
and the response is forwarded from the underlying Elasticsearch indices.

The endpoint filters the Elasticsearch projections from the underlying composite views and only returns results from
indices to which user has access to, i.e. has `views/query` permission.

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
