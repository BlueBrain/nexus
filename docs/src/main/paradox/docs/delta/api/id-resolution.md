# Id Resolution

Id Resolution allows to resolve a resource by providing only a resource identifier (the `@id` value of a resource). In
case there are multiple resources with
the same identifier across different projects, the response provides all choices for disambiguation.

@@@ note { .tip title="Authorization notes" }

When performing a request, the caller must have `resources/read` permission on the project each resource belongs to.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Resolve

```
GET /v1/resolve/{id}
```

where...

- `{id}`: the identifier of the resource to resolve (URL encoded value).

## Example

The following example shows how to perform a request and possible responses. If the provided identifier can be resolved
uniquely, the response is identical to that of the @ref:[resource fetch operation](resources-api.md#fetch). In case there are multiple choices, the
response is that of a @ref:[listing operation](resources-api.md#list) that filters for the resource identifier.

Request
:   @@snip [request.sh](assets/id-resolution/request.sh)

Response (single resource)
:   @@snip [response.json](assets/id-resolution/single-resolution-response.json)

Response (multiple choices)
:   @@snip [response.json](assets/id-resolution/multiple-resolution-response.json)

### Fusion Redirection

When querying the resolve endpoint, it is possible to add the `Accept: text/html` header in order for Nexus Delta to
redirect to the appropriate Nexus Fusion page.

## Resolve (Proxy Pass)

@@@ note { .warning }

This endpoint is designed for the Nexus deployment at the Blue Brain Project and as such might not suit the needs of
other deployments.

@@@

The @ref:[resolve](#resolve) endpoint offers resource resolution on the resources the caller has access to. As such, if
the client is a browser and does not have the ability to include an authorization header in the request, it is possible
to use the proxy pass version of the resolve endpoint which will lead the client to a Nexus Fusion authentication page.
This will allow to "inject" the user's token in a subsequent @ref:[resolve](#resolve) request made by Nexus Fusion.

### Configuration

* `app.fusion.base`: String - base URL for Nexus Fusion
* `app.fusion.enable-redirects`: Boolean - needs to be `true` in order for redirects to work (defaults to `false`)
* `app.fusion.resolve-base`: String - base URL to use when reconstructing the resource identifier in the
  proxy pass endpoint

### Redirection

1. The client calls `/v1/resolve-proxy-pass/{segment}`
2. Nexus Delta reconstructs the resource identifier
    * `{resourceId}` = `{resolveBase}/{segment}`
3. Nexus Delta redirects the client to...
    * the `{fusionBaseUrl}/resolve/{resourceId}` Fusion endpoint if the `Accept: text/html` header is present
    * the `/v1/resolve/{resourceId}` Delta endpoint otherwise

The Nexus Fusion resolve page allows the user to authenticate (if they are not already authenticated) and will perform a
call to the Nexus Delta `/v1/resolve/{resourceId}` with the user's authentication token.

All calls to the `/v1/resolve-proxy-pass` endpoint lead to
@link:[303 See Other responses](https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/303).

### Example

The example below assumes that:

* `{fusionBaseUrl}` = `http://localhost:8080/fusion`
* `{segment}` = `nexus/data/identifier`
* `{resolveBase}` = `https://example.com`

Request
:   @@snip [request.sh](assets/id-resolution/proxy-request.sh)

Redirect (when `Acccept:text/html` is provided)
:   @@snip [response.json](assets/id-resolution/proxy-response-fusion.html)

Redirect (no `Accept:text/html` provided)
:   @@snip [response.json](assets/id-resolution/proxy-response-delta.html)

#### Remark

In your networking setup, if a proxy pass is enabled to map `https://example.com/nexus/data/*`
to `https://localhost:8080/v1/resolve-proxy-pass/nexus/data/*`, the proxy pass allows to de facto resolve resource with
identifier of the type `https://example.com/nexus/data/*` by simply querying their `@id`.