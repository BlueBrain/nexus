# Global search

Nexus provides global search functionality across all projects through the @link:[search plugin](https://github.com/BlueBrain/nexus/tree/master/delta/plugins/search/src){ open=new }.

@@@ warning
The search plugin is experimental and its functionality and API can change without notice.
@@@

The plugin is disabled by default and requires following configuration:

- `plugins.search.enabled=true` - enables the plugin. Enabling the plugin will trigger creating a `CompositeView` in each project.
- `plugins.search.fields={pathToFile}` - specifies which fields are available for searching and/or aggregations. This also serves as configuration returned by the `/search/config` endpoint. It is currently only used by Nexus Fusion and can be omitted if not used. Example can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/tests/docker/config/fields.json){ open=new }.
- `plugins.search.indexing.context={pathToFile}` - the context which is used to transform the results of the SPARQL construct query into compacted JSON-LD which will be indexed in the ElasticSearch projection. Example can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/tests/docker/config/search-context.json){ open=new }.
- `plugins.search.indexing.mapping={pathToFile}` - the Elasticsearch mappings that will be used in the ElasticSearch projection. Example can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/tests/docker/config/mapping.json){ open=new }.
- `plugins.search.indexing.settings={pathToFile}`- additional Elasticsearch settings that will be used in the ElasticSearch projection. Example can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/tests/docker/config/settings.json){ open=new }.
- `plugins.search.indexing.query={pathToFile}` - SPARQL construct query that will be used to create triples which will be then compacted using the provided context and indexed in the ElasticSearch projection. Example can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/tests/docker/config/construct-query.sparql){ open=new }.
- `plugins.search.indexing.resource-types={pathToFile}` - the list of types which will be used to filter resources to be indexed in the ElasticSearch projection. Example can be found @link:[here](https://github.com/BlueBrain/nexus/blob/master/tests/docker/config/resource-types.json){ open=new }.

This plugin creates a @ref:[CompositeView](views/composite-view-api.md) with `https://bluebrain.github.io/nexus/vocabulary/searchView` id in each project, which can be queried using the endpoint provided by the plugin.

The view contains a single source(@ref:[ProjectEventStream](views/composite-view-api.md#projecteventstream)) and a single @ref:[ElasticSearchProjection](views/composite-view-api.md#elasticsearchprojection). The mappings and settings for the projection are defined by `plugins.search.indexing.mapping` and `plugins.search.indexing.settings` configuration files.
The resources are first filtered by types specified in `plugins.search.indexing.resource-types`
and then indexed into intermediate SPARQL space.
Then a SPARQL construct query defined by `plugins.search.indexing.query` is performed against the intermediate SPARQL space and the results are compacted using `plugins.search.indexing.context` and indexed into the Elasticsearch projection. 
More details about this process can be found in @ref:[CompositeViews Documentation](views/composite-view-api.md#processing-pipeline).

## Query

```
POST /v1/search/query
{payload}
```
... where `{payload}` is an @link:[Elasticsearch query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html){ open=new } and the response
is forwarded from the underlying Elasticsearch indices.
The endpoint filters the Elasticsearch projections from the underlying composite views and only returns results from indices to which user has access to, i.e. has `views/query` permission.


## Configuration
```
GET /v1/search/config
```

This endpoint returns the search configuration. The contents of `plugins.search.fields` config file are the payload of this response.
