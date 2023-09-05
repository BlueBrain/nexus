# Multi fetch

The multi-fetch operation allows to get in a given format multiple resources that can live in multiple projects.

The response includes a resources array that contains the resources in the order specified in the request. 
The structure of the returned resources is similar to that returned by the fetch API. 
If there is a failure getting a particular resource, the error is included in place of the resource.

This operation can be used to return every type of resource.

@@@ note { .tip title="Authorization notes" }

When performing a request, the caller must have `resources/read` permission on the project each resource belongs to.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Payload

```
GET /v1/multi-fetch/resources

{
  "format": {format}
  "resources": [
    {
      "id": "{id}",
      "project": "{project}"
    },
    ...
  ]
}
```

where...

- `{format}`: String - the format we expect for the resources in the response. 
Accepts the following values: source (to get the original payload), annotated-source (to get the original payload with metadata), compacted, expanded, n-triples, dot
- `{project}`: String - the project (in the format 'myorg/myproject') where the specified resource belongs. This field
  is optional. It defaults to the current project.
- `{id}`: Iri - the @id value of the resource to be returned. Can contain a tag or a revision.

## Example

The following example shows how to perform a multi-fetch and an example of response
containing errors (missing permissions and resource not found).
As a response, a regular json is returned containing the different resources in the requested format.

Request
:   @@snip [request.sh](assets/multi-fetch/request.sh)

Payload
:   @@snip [payload.json](assets/multi-fetch/payload.json)

Response
:   @@snip [response.json](assets/multi-fetch/response.json)

