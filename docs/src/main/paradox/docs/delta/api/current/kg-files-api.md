# Files

Files are attachment resources rooted in the `/v1/files/{org_label}/{project_label}/` collection.

Each file... 

- belongs to a `project` identifier by the label `{project_label}`
- inside an `organization` identifier by the label `{org_label}` 

Access to resources in the system depends on the access control list set for them. Depending on the access control list, 
a caller may need to prove its identity by means of an **access token** passed to the `Authorization` 
header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](authentication.md) to learn more about how 
to retrieve an access token.

@@@ note { .tip title="Authorization notes" }	

When modifying files, the caller must have the permissions defined on the storage associated to the file on the current 
path of the project or the ancestor paths.

When  reading files, the caller must have the permissions defined on the storage associated to the file on the current 
path of the project or the ancestor paths.

@@@

## Create a file using POST

```
POST /v1/files/{org_label}/{project_label}
```

The json payload:

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is 
the `prefix` defined on the resource's project (`{project_label}`).

**Example**

Request
:   @@snip [file.sh](assets/files/file.sh)

Response
:   @@snip [file-created.json](assets/files/file-created.json)

## Create a file using PUT

This alternative endpoint to create a resource is useful in case the json payload does not contain an `@id` but you want
to specify one. The @id will be specified in the last segment of the endpoint URI.

```
PUT /v1/files/{org_label}/{project_label}/{file_id}
```

Note that if the payload contains an @id different from the `{file_id}`, the request will fail.

**Example**

Request
:   @@snip [file-put.sh](assets/files/file-put.sh)

Response
:   @@snip [file-put-created.json](assets/files/file-put-created.json)

## Create a file (specific storage)

```
POST /v1/files/{org_label}/{project_label}?storage={storageId}
```

Or

```
PUT /v1/files/{org_label}/{project_label}/{file_id}?storage={storageId}
```

... where `{storageId}` selects a specific storage backend where the file will be uploaded.

**Example**

Request
:   @@snip [file-post-storageid.sh](assets/files/file-post-storageid.sh)

Response
:   @@snip [file-created.json](assets/files/file-created.json)

## Link an existing file using POST

Creates a resource from an existing file, provided that the storage backend where it is located supports the operation.

```
POST /v1/files/{org_label}/{project_label}?storage={storageId}
  {
    "filename": "myfile.png",
    "path": "relative/path/to/myfile.png",
    "mediaType": "image/png"
  }
```

... where `{storageId}` selects a specific storage backend that supports linking existing files.

**Example**

Request
:   @@snip [file-link.sh](assets/files/file-link.sh)

Payload
:   @@snip [file-link.json](assets/files/file-link.json)

Response
:   @@snip [file-link-created.json](assets/files/file-link-created.json)

## Link an existing file using PUT

Creates a resource from an existing file, provided that the storage backend where it is located supports the operation.

This alternative endpoint allows to specify the resource `@id`.

```
PUT /v1/files/{org_label}/{project_label}/{file_id}?storage={storageId}
  {
    "filename": "myfile.png",
    "path": "relative/path/to/myfile.png",
    "mediaType": "image/png"
  }
```

... where `{storageId}` selects a specific storage backend that supports linking existing files.

**Example**

Request
:   @@snip [file-link-put.sh](assets/files/file-link-put.sh)

Payload
:   @@snip [file-link.json](assets/files/file-link.json)

Response
:   @@snip [file-link-created.json](assets/files/file-link-created.json)

## Update a file

This operation overrides the payload.

In order to ensure a client does not perform any changes to a file without having had seen the previous revision of
the file, the last revision needs to be passed as a query parameter.

```
PUT /v1/files/{org_label}/{project_label}/{resource_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resource.


**Example**

Request
:   @@snip [file-update.sh](assets/files/file-update.sh)

Response
:   @@snip [file-updated.json](assets/files/file-updated.json)


## Tag a file

Links a file revision to a specific name.

Tagging a file is considered to be an update as well.

```
POST /v1/files/{org_label}/{project_label}/{file_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```

... where

- `{previous_rev}`: is the last known revision number for the file.
- `{name}`: String - label given to the file at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [file-tag.sh](assets/files/file-tag.sh)

Payload
:   @@snip [tag.json](assets/files/file-tag.json)

Response
:   @@snip [file-tagged.json](assets/files/file-tagged.json)

## Deprecate a file

Locks the file, so no further operations can be performed.

Deprecating a file is considered to be an update as well. 

```
DELETE /v1/files/{org_label}/{project_label}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the file.

**Example**

Request
:   @@snip [file-deprecate.sh](assets/files/file-deprecate.sh)

Response
:   @@snip [file-deprecated.json](assets/files/file-deprecated.json)

## Fetch a file

When fetching a file, the response format can be chosen through HTTP content negotiation, using the **Accept** HTTP header.

- **application/ld+json**: JSON-LD output response to retrieve the file metadata. Further specifying the query 
  parameter `format=compacted|expanded` will provide with the JSON-LD 
  @link:[compacted document form](https://www.w3.org/TR/json-ld11/#compacted-document-form){ open=new } or the 
  @link:[expanded document form](https://www.w3.org/TR/json-ld11/#expanded-document-form){ open=new }.
- **\*/\***: retrieves the file content.
- for any other Content-Type that matches the file Content-Type, the file content will be fetched as well.

```
GET /v1/files/{org_label}/{project_label}/{file_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request (binary)
:   @@snip [file-fetch.sh](assets/files/file-fetch.sh)

Request (metadata)
:   @@snip [file-fetch-meta.sh](assets/files/file-fetch-meta.sh)

Response
:   @@snip [file-fetched-meta.json](assets/files/file-fetched-meta.json)

## List files

```
GET /v1/files/{org_label}/{project_label}?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&q={search}&sort={sort}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting files based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting files based on their revision value
- `{type}`: Iri - can be used to filter the resulting files based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting files based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting files based on the person which performed the last update
- `{search}`: String - can be provided to select only the files in the collection that have attribute values matching 
  (containing) the provided string
- `{sort}`: String - can be used to sort files based on a payloads' field. This parameter can appear multiple times to 
  enable sorting by multiple fields

**Example**

Request
:   @@snip [files-list.sh](assets/files/files-list.sh)

Response
:   @@snip [files-list.json](assets/files/files-list.json)
