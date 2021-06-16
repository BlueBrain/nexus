# Archives

An archive is a collection of resources stored inside an archive file. The archiving format chosen for this purpose is 
tar (or tarball). Archive resources are rooted in the `/v1/archives/{org_label}/{project_label}/` collection.

Each archive... 

- belongs to a `project` identifier by the label `{project_label}`
- inside an `organization` identifier by the label `{org_label}`

@@@ note { .tip title="Authorization notes" }	

When modifying archives, the caller must have `archives/write` permissions on the current path of the project or the 
ancestor paths.

When reading archives, the caller must have `resources/read` permissions on the current path of the project or the 
ancestor paths.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Lifecycle

Contrarily to the rest of the platform resources, archives are not persisted resources, given their nature. Therefore 
there are no update, tag or deprecation operations available on archive resources.

An archive resource will be automatically erased from the system after certain after certain time. This time is 
configurable (config property `app.archives.cache-invalidate-after`) and it defaults to 5 hours.

## Payload

```
{
    "resources" : [
        {
            "@type": "Resource",
            "resourceId": "{resource_id}",
            "project": "{project}",
            "path": "{path}",
            "originalSource": "{originalSource}",
            "format": "{format}",
            "rev": "{rev}",
            "tag": "{tag}"
        },
        {
            "@type": "File",
            "resourceId": "{resource_id}",
            "project": "{project}",
            "path": "{path}",
            "rev": "{rev}",
            "tag": "{tag}"
        },
        {...}       
    ]
}
```

where...

- `{resource_id}`: Iri - the @id value of the resource to be added to the archive.
- `{project}`: String - the project (in the format 'myorg/myproject') where the specified resource belongs. This field 
  is optional. It defaults to the current project.
- `{path}`: Path - the relative path on the archive where this resource is going to stored
    * Optional / defaults to `{project}/{resourceId}.json` for a Resource type and `{project}/{filename}` for File type.
    * The provided filename should not exceed 99 characters.
- `{originalSource}`: Boolean - flag to decide the whether to fetch the original payload or its compacted form. 
    * Only allowed for Resource type
    * Optional and defaults to false
    * Can not be present at the same time as `format` field.
- `{format}`: String - the format we expect for the resource in the archive.
    * Only allowed for Resource type
    * Optional and defaults to compacted
    * Accepts the following values: source (to get the original payload), compacted, expanded, n-triples, dot
    * Can not be present at the same time as `originalSource` field.
- `{rev}`: Long - the revision of the resource. This field is optional. It defaults to the latest revision.
- `{tag}`: String - the tag of the resource. This field is optional. This field cannot be present at the same time as 
  `rev` field.

In order to decide whether we want to select a resource or a file, the `@type` discriminator is used with the following 
possibilities:

- `Resource`: targets a resource
- `File`: targets a file

## Create using POST

This endpoint is used to describe the archive and to subsequently consume it.
```
POST /v1/archives/{org_label}/{project_label}
```

The json payload:

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is 
  the `prefix` defined on the resource's project (`{project_label}`).

The response will be an HTTP 303 Location redirect, which will point to the url where to consume the archive (tarball).

The following diagram can help to understand the HTTP exchange
![post-redirect-get](assets/archives/post-redirect-get.png "Post/Redirect/Get archive")

**Example**

The following example shows how to create an archive containing 3 files. 2 of them are resources and the other is a file.
As a response, the tarball will be offered.

Request
:   @@snip [archive.sh](assets/archives/create.sh)

Payload
:   @@snip [archive.json](assets/archives/payload.json)


## Create using PUT

This alternative endpoint to create an archive is useful in case you want to split the creation of the archive resource 
and the consumption of it. 

It can also be useful in cases where one user wants to create the definition of the archive and share the link with 
another user who then is going to consume it.

```
PUT /v1/archives/{org_label}/{project_label}/{archive_id}
```

**Example**

Request
:   @@snip [create-put.sh](assets/archives/create-put.sh)

Payload
:   @@snip [payload.json](assets/archives/payload.json)

Response
:   @@snip [created.json](assets/archives/created.json)

Note that if the payload contains an @id different from the `{archive_id}`, the request will fail.

## Fetch

When fetching an archive, the response format can be chosen through HTTP content negotiation.
In order to fetch the archive metadata, the client can use any of the @ref:[following MIME types](content-negotiation.md#supported-mime-types).
However, in order to fetch the archive content, the HTTP `Accept` header  `*/*` or `application/x-tar` should be provided.

```
GET /v1/archives/{org_label}/{project_label}/{archive_id}
```

**Example**

Request (tarball)
:   @@snip [fetch.sh](assets/archives/fetch.sh)

Request (metadata)
:   @@snip [fetchMeta.sh](assets/archives/fetchMeta.sh)

Response
:   @@snip [fetched.json](assets/archives/fetched.json)