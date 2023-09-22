# Id Resolution

Id Resolution allows to resolve a resource by providing only a resource identifier (the `@id` value of a resource). In
case there are multiple resources with
the same identifier across different project, the response provides all choices for disambiguation.

@@@ note { .tip title="Authorization notes" }

When performing a request, the caller must have `resources/read` permission on the project each resource belongs to.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Payload

```
GET /v1/resolve/{id}
```

where...

- `{id}`: the identifier of the resource to resolve (URL encoded value).

## Example

The following example shows how to perform a request and possible responses. If the provided identifier can be resolved
uniquely, the response is identical to that of the [resource fetch operation](resources-api.md#fetch). In case there are multiple choices, the
response is that of a [listing operation](resources-api.md#list) that filters for the resource identifier.

Request
:   @@snip [request.sh](assets/id-resolution/request.sh)

Response (single resource)
:   @@snip [response.json](assets/id-resolution/single-resolution-response.json)

Response (multiple choices)
:   @@snip [response.json](assets/id-resolution/multiple-resolution-response.json)

