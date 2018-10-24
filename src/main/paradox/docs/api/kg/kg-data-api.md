# Data

Generic resources are rooted in the `/v1/data/{org_label}/{project_label}/` collection. Everything described here can be achieved similarly using the @ref[operations on resources](./kg-resources-api.md) api.

Each resource... 

- belongs to a `project` identifier by the label `{project_label}`
- inside an `organization` identifier by the label `{org_label}` 

Any resources in the system might be protected using an **access token**, provided by the HTTP header `Authorization: Bearer {access_token}`. Visit @ref:[Authentication](../iam-service-api.md) in order to learn more about how to retrieve an access token.

@@@ note { .tip title="Running examples with Postman" }

The simplest way to explore our API is using [Postman](https://www.getpostman.com/apps). Once downloaded, import the [resources collection](../assets/data-postman.json).

If your deployment is protected by an access token: 

Edit the imported collection -> Click on the `Authorization` tab -> Fill the token field.

@@@


## Update a resource

This operation overrides the payload.

In order to ensure a client does not perform any changes to a resource without having had seen the previous revision of
the resource, the last revision needs to be passed as a query parameter.

```
PUT /v1/data/{org_label}/{project_label}/{resource_id}?rev={previous_rev}
  {...}
```
... where `{previous_rev}` is the last known revision number for the resource.


**Example**

Request
:   @@snip [data-update.sh](../assets/data-update.sh)

Payload
:   @@snip [resource.json](../assets/resource.json)

Response
:   @@snip [resource-ref-new-updated.json](../assets/resource-ref-new-updated.json)


## Tag a resource

Links a resource revision to a specific name. 

Tagging a resource is considered to be an update as well.

```
PUT /v1/data/{org_label}/{project_label}/{resource_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```
... where 

- `{previous_rev}`: is the last known revision number for the resource.
- `{name}`: String - label given to the resources at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [data-tag.sh](../assets/data-tag.sh)

Payload
:   @@snip [tag.json](../assets/tag.json)

Response
:   @@snip [resource-ref-new-tagged.json](../assets/resource-ref-new-tagged.json)


## Deprecate a resource

Locks the resource, so no further operations can be performed. It also deletes the resource from listing/querying results.

Deprecating a resource is considered to be an update as well. 

```
DELETE /v1/data/{org_label}/{project_label}/{resource_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resource.

**Example**

Request
:   @@snip [data-deprecate.sh](../assets/resource-deprecate.sh)

Response
:   @@snip [resource-ref-new-deprecated.json](../assets/resource-ref-new-deprecated.json)


## Fetch a resource (current version)

```
GET /v1/data/{org_label}/{project_label}/{resource_id}
```

**Example**

Request
:   @@snip [data-fetch.sh](../assets/data-fetch.sh)

Response
:   @@snip [resource-fetched.json](../assets/resource-fetched.json)


## Fetch a resource (specific version)

```
GET /v1/data/{org_label}/{project_label}/{resource_id}?rev={rev}
```
... where `{rev}` is the revision number of the resource to be retrieved.

**Example**

Request
:   @@snip [data-fetch-revision.sh](../assets/data-fetch-revision.sh)

Response
:   @@snip [resource-fetched.json](../assets/resource-fetched.json)


## Fetch a resource (specific tag)

```
GET /v1/data/{org_label}/{project_label}/{resource_id}?tag={tag}
```

... where `{tag}` is the tag of the resource to be retrieved.


**Example**

Request
:   @@snip [data-fetch-tag.sh](../assets/data-fetch-tag.sh)

Response
:   @@snip [resource-fetched-tag.json](../assets/resource-fetched-tag.json)


## List resources

```
GET /v1/data/{org_label}/{project_label}?from={from}&size={size}&deprecated={deprecated}&q={full_text_search_query}
```

where...

- `{full_text_search_query}`: String - can be provided to select only the resources in the collection that have attribute values matching (containing) the provided token; when this field is provided the results will also include score values for each result
- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting resources based on their deprecation status


**Example**

Request
:   @@snip [data-list.sh](../assets/data-list.sh)

Response
:   @@snip [resources-list.json](../assets/resources-list.json)