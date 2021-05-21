# AggregateElasticSearchView

This view is an aggregate of ElasticSearchViews. The view itself does not create any index, but it references the 
already existing indices of the linked ElasticSearchViews.

@@@ note

From Delta version 1.5.0,  `AggregateElasticSearchView` can point to other `AggregateElasticSearchView`s. 

@@@

When performing queries on the `_search` endpoint, this view will make use of the 
@link:[multi-index](https://www.elastic.co/guide/en/elasticsearch/reference/current/multi-index.html){ open=new } query 
capabilities of ElasticSearch in order to select the indices of every view present on this aggregate view.

If the caller does not have the permission views/query (or from v1.5, the user-defined permission) on all the views defined on the aggregated view,
only a subset of indices (or none) will be selected, respecting the defined permissions.

![Aggregate ElasticSearchView](../assets/views/elasticsearch/aggregate-view.png "Aggregate ElasticSearchView")

## Payload

```json
{
  "@id": "{someid}",
  "@type": "AggregateElasticSearchView",
  "views": [ 
    {
        "project": "{project}",
        "viewId": "{viewId}"
    },
    ...
  ]
}
```

where...
 
- `{project}`: String - The project, defined as `{org_label}/{project_label}`, where the `{viewId}` is located.
- `{viewId}`: Iri - The view @id value to be aggregated.

## Create using POST

```
POST /v1/views/{org_label}/{project_label}
  {...}
```

The json payload:

- If the `@id` value is found on the payload, this `@id` will be used.
- If the `@id` value is not found on the payload, an `@id` will be generated as follows: `base:{UUID}`. The `base` is the
  `prefix` defined on the resource's project (`{project_label}`).

**Example**

Request
:   @@snip [create.sh](../assets/views/elasticsearch/create-aggregate.sh)

Payload
:   @@snip [payload.json](../assets/views/elasticsearch/payload-aggregate.json)

Response
:   @@snip [created.json](../assets/views/elasticsearch/created-aggregate.json)

## Create using PUT

This alternative endpoint to create a view is useful in case the json payload does not contain an `@id` but you want to
specify one. The `@id` will be specified in the last segment of the endpoint URI.

```
PUT /v1/views/{org_label}/{project_label}/{view_id}
  {...}
```

Note that if the payload contains an `@id` different from the `{view_id}`, the request will fail.

**Example**

Request
:   @@snip [create-put.sh](../assets/views/elasticsearch/create-put-aggregate.sh)

Payload
:   @@snip [payload.json](../assets/views/elasticsearch/payload-aggregate.json)

Response
:   @@snip [created.json](../assets/views/elasticsearch/created-put-aggregate.json)

## Update

This operation overrides the payload.

In order to ensure a client does not perform any changes to a resource without having had seen the previous revision of
the view, the last revision needs to be passed as a query parameter.

```
PUT /v1/views/{org_label}/{project_label}/{view_id}?rev={previous_rev}
  {...}
```

... where `{previous_rev}` is the last known revision number for the view.

**Example**

Request
:   @@snip [update.sh](../assets/views/elasticsearch/update-aggregate.sh)

Payload
:   @@snip [payload.json](../assets/views/elasticsearch/payload-aggregate.json)

Response
:   @@snip [updated.json](../assets/views/elasticsearch/updated-aggregate.json)

## Tag

Links a view revision to a specific name.

Tagging a view is considered to be an update as well.

```
POST /v1/views/{org_label}/{project_label}/{view_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```

... where

- `{previous_rev}`: is the last known revision number for the resource.
- `{name}`: String - label given to the view at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [tag.sh](../assets/views/elasticsearch/tag-aggregate.sh)

Payload
:   @@snip [tag.json](../assets/tag.json)

Response
:   @@snip [tagged.json](../assets/views/elasticsearch/tagged-aggregate.json)

## Deprecate

Locks the view, so no further operations can be performed.

Deprecating a view is considered to be an update as well.

@@@ note { .warning }

Deprecating a view makes the view not searchable.

@@@

```
DELETE /v1/views/{org_label}/{project_label}/{view_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the view.

**Example**

Request
:   @@snip [deprecate.sh](../assets/views/elasticsearch/deprecate-aggregate.sh)

Response
:   @@snip [deprecated.json](../assets/views/elasticsearch/deprecated-aggregate.json)

## Fetch

```
GET /v1/views/{org_label}/{project_label}/{view_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
  `{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch.sh](../assets/views/elasticsearch/fetch-aggregate.sh)

Response
:   @@snip [fetched.json](../assets/views/elasticsearch/fetched-aggregate.json)

## Fetch original payload

```
GET /v1/views/{org_label}/{project_label}/{view_id}/source?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.
  `{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetchSource.sh](../assets/views/elasticsearch/fetch-source-aggregate.sh)

Response
:   @@snip [fetched.json](../assets/views/elasticsearch/payload-aggregate.json)


## Fetch tags

```
GET /v1/views/{org_label}/{project_label}/{view_id}/tags?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch_tags.sh](../assets/views/elasticsearch/tags-aggregate.sh)

Response
:   @@snip [tags.json](../assets/tags.json)


## Search Documents

Provides aggregated search functionality across all the `ElasticSearchView`s referenced from the target `view_id`.

```
POST /v1/views/{org_label}/{project_label}/{view_id}/_search
  {...}
```
The supported payload is defined on the 
@link:[ElasticSearch documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html){ open=new }

The string `documents` is used as a prefix of the default ElasticSearch `view_id`

**Example**

Request
:   @@snip [elastic-view-search.sh](../assets/views/elasticsearch/search-aggregate.sh)

Payload
:   @@snip [elastic-view-payload.json](../assets/views/elasticsearch/search-payload.json)

Response
:   @@snip [elastic-view-search.json](../assets/views/elasticsearch/search-results.json)
