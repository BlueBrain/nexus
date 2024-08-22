# History of resources

The history endpoint allows to get an overview of the differents events and actions which occured 
during the lifetime of a resource (creation/update/tag/deprecation) for the different types of resources 
(generic resources, schemas, files).

@@@ note { .tip title="Authorization notes" }

When performing a request, the caller must have `resources/read` permission on the project the resource belongs to.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

@@@ note { .warning }

The described endpoints are experimental and the responses structure might change in the future.

@@@

```
GET /v1/history/{org_label}/{project_label}/{id}
```

where...

- `{id}`: the identifier of the resource to resolve (URL encoded value).

**Example**

Request
:   @@snip [request.sh](assets/history/request.sh)

Response
:   @@snip [response.json](assets/history/response.json)

