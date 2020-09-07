# Operating on resources

Access to resources in the system depends on the access control list set for them. Depending on the access control list, 
a caller may need to prove its identity by means of an **access token** passed to the `Authorization` 
header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](authentication.md) to learn more about how 
to retrieve an access token.

All resources in the system share a base set of operations. Assuming a nexus deployment at
`http(s)://nexus.example.com` resource address of `/v1/{address}` the following operations should apply to most (all)
resources:

### Fetch the current revision of the resource

```
GET /v1/{address}
```

**Status Codes**

- **200 OK**: the resource is found and returned successfully
- **404 Not Found**: the resource was not found

### Fetch a specific revision of the resource

```
GET /v1/{address}?rev={rev}
```
... where `{rev}` is the revision number, starting at `1`.

**Status Codes**

- **200 OK**: the resource revision is found and returned successfully
- **404 Not Found**: the resource revision was not found

### Fetch a specific tag of the resource

```
GET /v1/{address}?tag={tag}
```
... where `{tag}` is the tag name linked to a certain revision number.

**Status Codes**

- **200 OK**: the resource tag is found and returned successfully
- **404 Not Found**: the resource tag was not found

### Create a new resource

Depending on whether the resource is a singleton resource or is part of a wider collection of resources of the same
type the verbs `POST` and `PUT` are used.

For a singleton resource:

```
PUT /v1/{address}
{...}
```

For a collection resources:

```
POST /v1/{collection_address}
{...}
```
... where `{collection_address}` is the address of the collection the resource belongs to.

**Status Codes**

- **201 Created**: the resource was created successfully
- **400 Bad Request**: the resource is not valid or cannot be created at this time
- **409 Conflict**: the resource already exists

### Update a resource

In order to ensure a client does not perform any changes to a resource without having had seen the previous revision of
the resource, the last revision needs to be passed as a query parameter.

```
PUT /v1/{address}?rev={previous_rev}
{...}
```

**Status Codes**

- **200 OK**: the resource was created successfully
- **400 Bad Request**: the resource is not valid or cannot be updated at this time
- **409 Conflict**: the provided revision is not the current resource revision number


### Tag a resource

Links a resource revision to a specific name. 

Tagging a resource is considered to be an update as well.

```
PUT /v1/{address}/tags?rev={previous_rev}
{
   "tag": "{name}",
   "rev": {rev}
}
```
... where:

- `{name}`: String - the name given to the resource at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.


### Deprecate a resource

Locks the resource, so no further operations can be performed. It also deletes the resource from listing/querying 
results.

Deprecating a resource is considered to be an update as well. 

```
DELETE /v1/{address}?rev={previous_rev}
```

**Status Codes**

- **200 OK**: the resource was created successfully
- **400 Bad Request**: the resource is not valid or cannot be deprecated at this time
- **409 Conflict**: the provided revision is not the current resource revision number

**Status Codes**

- **200 OK**: the resource was created successfully
- **400 Bad Request**: the resource is not valid or cannot be created at this time
- **409 Conflict**: the provided revision is not the current resource revision number

## Listing

```
GET /v1/{collection_address}?from={from}&size={size}&{deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}
```

... where all of the query parameters are individually optional.

- `{collection_address}` Path - the selected collection to list, filter or search; for example: `/v1/projects/`, 
  `/v1/schemas/{org_label}/{project}`,
- `{from}`: Number - the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting resources based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting resources based on their revision value
- `{type}`: Iri - can be used to filter the resulting resources based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting resources based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting resources based on the person which performed the last update

- **Note**: Some endpoints might not support some of the above query parameters. Please check the specific section of 
  the documentation related tot he endpoint you want to consume for more details.

### List response format

The response to any search requests follows the described format:

```
  "_total": {hits},
  "_maxScore": {maxScore},
  "_next": "{next_page_address}",
  "_results": [
    {
      "@id": "{resource_id}",
      ...
    },
    {
      "@id": "{resource_id}",
      ...
    }
  ]
```

... where:

- `{hits}`: Number - the total number of results found for the requested search.
- `{maxScore}` Float - the maximum score found across all hits.
- `{resource_id}` Iri - the qualified id for one of the results.

The relationship `_next` at the top level offer discovery of more resources, in terms of navigation/pagination. 

The fields `{maxScore}` and `{score_id}` are optional fields and will only be present whenever a `q` query parameter is 
provided on the request.