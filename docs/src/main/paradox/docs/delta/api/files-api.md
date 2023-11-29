# Files

Files are attachment resources rooted in the `/v1/files/{org_label}/{project_label}/` collection.

Each file belongs to a `project` identifier by the label `{project_label}` inside an `organization` identifier by the label `{org_label}`.


@@@ note { .tip title="Authorization notes" }	

When creating, updating and reading files, the caller must have the permissions defined on the storage associated to the file on the current 
path of the project or the ancestor paths.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Nexus metadata

When using the endpoints described on this page, the responses will contain global metadata described on the
@ref:[Nexus Metadata](../metadata.md) page. In addition, the following files specific metadata can be present

- `_bytes`: size of the file in bytes
- `_digest`: algorithm and checksum used for file integrity
- `_filename`: name of the file
- `_location`: path where the file is stored on the underlying storage
- `_mediaType`: @link:[MIME](https://en.wikipedia.org/wiki/MIME){ open=new } specifying the type of the file
- `_origin`: whether the file attributes resulted from an action taken by a client or the Nexus Storage Service
- `_storage`: `@id`, `@type`, and revision of the @ref:[Storage](storages-api.md) used for the file
- `_uuid`: @link:[UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier){ open=new } of the file
- `_project`: address of the file's project
- `_incoming`: address to query to obtain the @ref:[list of incoming links](resources-api.md#list-incoming-links)
- `_outgoing`: address to query to obtain the @ref:[list of outgoing links](resources-api.md#list-outgoing-links)


## Indexing

All the API calls modifying a file (creation, update, tagging, deprecation) can specify whether the file should be indexed
synchronously or in the background. This behaviour is controlled using `indexing` query param, which can be one of two values:

- `async` - (default value) the file will be indexed asynchronously
- `sync` - the file will be indexed synchronously and the API call won't return until the indexing is finished

## Create using POST

```
POST /v1/files/{org_label}/{project_label}?storage={storageId}&tag={tagName}
```

... where 
- `{storageId}` selects a specific storage backend where the file will be uploaded. This field is optional.
When not specified, the default storage of the project is used.
- `{tagName}` an optional label given to the file on its first revision.

The json payload:

- If the `@id` value is found on the payload, this @id will be used.
- If the `@id` value is not found on the payload, an @id will be generated as follows: `base:{UUID}`. The `base` is 
the `prefix` defined on the resource's project (`{project_label}`).

**Example**

Request
:   @@snip [create-post.sh](assets/files/create-post.sh)

Response
:   @@snip [created-post.json](assets/files/created-post.json)

## Create using PUT

This alternative endpoint to create a resource is useful in case the json payload does not contain an `@id` but you want
to specify one. The @id will be specified in the last segment of the endpoint URI.

```
PUT /v1/files/{org_label}/{project_label}/{file_id}?storage={storageId}&tag={tagName}
```

... where 
- `{storageId}` selects a specific storage backend where the file will be uploaded. This field is optional. 
When not specified, the default storage of the project is used.
- `{tagName}` an optional label given to the file on its first revision.

Note that if the payload contains an @id different from the `{file_id}`, the request will fail.

**Example**

Request
:   @@snip [create-put.sh](assets/files/create-put.sh)

Response
:   @@snip [created-put.json](assets/files/created-put.json)

## Create copy using POST or PUT

Create a file copy based on a source file potentially in a different organization. No `MIME` details are necessary since this is not a file upload. Metadata such as the size and digest of the source file are preserved.

The caller must have the following permissions:
- `files/read` on the source project.
- `storages/write` on the storage in the destination project.

Either `POST` or `PUT` can be used to copy a file, as with other creation operations. These REST resources are in the context of the **destination** file; the one being created.
- `POST` will generate a new UUID for the file:
    ```
    POST /v1/files/{org_label}/{project_label}?storage={storageId}&tag={tagName}
    ```
- `PUT` accepts a `{file_id}` from the user:
    ```
    PUT /v1/files/{org_label}/{project_label}/{file_id}?storage={storageId}&tag={tagName}
    ```
  
... where
- `{storageId}` optionally selects a specific storage backend for the new file. The `@type` of this storage must be `DiskStorage` or `RemoteDiskStorage`.
  If omitted, the default storage of the project is used. The request will be rejected if there's not enough space on the storage.
- `{tagName}` an optional label given to the new file on its first revision.

Both requests accept the following JSON payload:
```json
{
  "destinationFilename": "{destinationFilename}",
  "sourceProjectRef": "{sourceOrg}/{sourceProj}",
  "sourceFileId": "{sourceFileId}",
  "sourceTag": "{sourceTagName}",
  "sourceRev": "{sourceRev}"
}
```

... where
- `{destinationFilename}` the optional filename for the new file. If omitted, the source filename will be used.
- `{sourceOrg}` the organization label of the source file.
- `{sourceProj}` the project label of the source file.
- `{sourceFileId}` the unique identifier of the source file.
- `{sourceTagName}` the optional source revision to be fetched.
- `{sourceRev}` the optional source tag to be fetched.

Notes:

- The storage type of `sourceFileId` must match that of the destination file. For example, if the destination `storageId` is omitted, the source storage must be of type `DiskStorage` (the default storage type).
- `sourceTagName` and `sourceRev` cannot be simultaneously present. If neither are present, the latest revision of the source file will be used.

**Example**

Request
:   @@snip [copy-put.sh](assets/files/copy-put.sh)

Response
:   @@snip [copy-put.json](assets/files/copy-put.json)


## Link using POST

Brings a file existing in a storage to Nexus Delta as a file resource. This operation is supported for files using `S3Storage` and `RemoteDiskStorage`.

```
POST /v1/files/{org_label}/{project_label}?storage={storageId}&tag={tagName}
  {
    "path": "{path}",
    "filename": "{filename}",
    "mediaType": "{mediaType}"
  }
```

- `{storageId}`: String - Selects a specific storage backend that supports linking existing files. This field is optional.
  When not specified, the default storage of the project is used.
- `{path}`: String - the relative location (from the point of view of storage folder) on the remote storage where the file exists.
- `{filename}`: String - the name that will be given to the file during linking. This field is optional. When not specified, the original filename is retained.
- `{mediaType}`: String - the MediaType fo the file. This field is optional. When not specified, Nexus Delta will attempt to detect it.
- `{tagName}` an optional label given to the linked file resource on its first revision.

**Example**

Request
:   @@snip [link-post.sh](assets/files/link-post.sh)

Payload
:   @@snip [link-post.json](assets/files/link-post.json)

Response
:   @@snip [linked-post.json](assets/files/linked-post.json)

## Link using PUT

Brings a file existing in a storage to Nexus Delta as a file resource. This operation is supported for files using `S3Storage` and `RemoteDiskStorage`.

This alternative endpoint allows to specify the resource `@id`.

```
PUT /v1/files/{org_label}/{project_label}/{file_id}?storage={storageId}&tag={tagName}
  {
    "path": "{path}",
    "filename": "{filename}",
    "mediaType": "{mediaType}"
  }
```

... where 

- `{storageId}`: String - Selects a specific storage backend that supports linking existing files. This field is optional.
When not specified, the default storage of the project is used.
- `{path}`: String - the relative location (from the point of view of the storage folder) on the remote storage where the file exists.
- `{filename}`: String - the name that will be given to the file during linking. This field is optional. When not specified, the original filename is retained.
- `{mediaType}`: String - the MediaType fo the file. This field is optional. When not specified, Nexus Delta will attempt to detect it.
- `{tagName}` an optional label given to the linked file resource on its first revision.

**Example**

Request
:   @@snip [link-put.sh](assets/files/link-put.sh)

Payload
:   @@snip [link-put.json](assets/files/link-put.json)

Response
:   @@snip [linked-put.json](assets/files/linked-put.json)

## Update

This operation overrides the file content.

In order to ensure a client does not perform any changes to a file without having had seen the previous revision of
the file, the last revision needs to be passed as a query parameter.

```
PUT /v1/files/{org_label}/{project_label}/{resource_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resource.

**Example**

Request
:   @@snip [update.sh](assets/files/update.sh)

Response
:   @@snip [updated.json](assets/files/updated.json)


## Tag

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
:   @@snip [tag.sh](assets/files/tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [tagged.json](assets/files/tagged.json)

## Remove tag

Removes a given tag.

Removing a tag is considered to be an update as well.

```
DELETE /v1/files/{org_label}/{project_label}/{file_id}/tags/{tag_name}?rev={previous_rev}
```
... where

- `{previous_rev}`: is the last known revision number for the resource.
- `{tag_name}`: String - label of the tag to remove.

**Example**

Request
:   @@snip [tag.sh](assets/files/delete-tag.sh)

Response
:   @@snip [tagged.json](assets/files/tagged.json)

## Deprecate

Locks the file, so no further operations can be performed.

Deprecating a file is considered to be an update as well. 

```
DELETE /v1/files/{org_label}/{project_label}/{file_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the file.

**Example**

Request
:   @@snip [deprecate.sh](assets/files/deprecate.sh)

Response
:   @@snip [deprecated.json](assets/files/deprecated.json)

## Undeprecate

Unlocks a previously deprecated file. Further operations can then be performed. The file will again be found when listing/querying.

Undeprecating a file is considered to be an update as well.

```
PUT /v1/file/{org_label}/{project_label}/{file_id}/undeprecate?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the resource.

**Example**

Request
:   @@snip [undeprecate.sh](assets/files/undeprecate.sh)

Response
:   @@snip [undeprecated.json](assets/files/undeprecated.json)

## Fetch

When fetching a file, the response format can be chosen through HTTP content negotiation. 
In order to fetch the file metadata, the client can use any of the @ref:[following MIME types](content-negotiation.md#supported-mime-types).
However, in order to fetch the file content, the HTTP `Accept` header  `*/*` (or any MIME type that matches the file MediaType) should be provided.

```
GET /v1/files/{org_label}/{project_label}/{file_id}?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request (binary)
:   @@snip [fetch.sh](assets/files/fetch.sh)

Request (metadata)
:   @@snip [fetch-metadata.sh](assets/files/fetch-metadata.sh)

Response  (metadata)
:   @@snip [fetched-metadata.json](assets/files/fetched-metadata.json)

If the @ref:[redirect to Fusion feature](../../getting-started/running-nexus/configuration/index.md#fusion-configuration) is enabled and
if the `Accept` header is set to `text/html`, a redirection to the fusion representation of the resource will be returned.

## Fetch tags

Retrieves all the tags available for the `{file_id}`.

```
GET /v1/files/{org_label}/{project_label}/{file_id}/tags?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision of the tags to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag of the tags to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch-tags.sh](assets/files/fetch-tags.sh)

Response
:   @@snip [fetched-tags.json](assets/tags.json)

## List

There are three available endpoints to list files in different scopes.

### Within a project

```
GET /v1/files/{org_label}/{project_label}?from={from}
                                         &size={size}
                                         &deprecated={deprecated}
                                         &rev={rev}
                                         &type={type}
                                         &createdBy={createdBy}
                                         &updatedBy={updatedBy}
                                         &q={search}
                                         &sort={sort}
                                         &aggregations={aggregations}
```

### Within an organization

This operation returns only files from projects defined in the organisation `{org_label}` and where the caller has the `resources/read` permission.

```
GET /v1/files/{org_label}?from={from}
                         &size={size}
                         &deprecated={deprecated}
                         &rev={rev}
                         &type={type}
                         &createdBy={createdBy}
                         &updatedBy={updatedBy}
                         &q={search}
                         &sort={sort}
                         &aggregations={aggregations}
```

### Within all projects

This operation returns only files from projects where the caller has the `resources/read` permission.

```
GET /v1/files?from={from}
             &size={size}
             &deprecated={deprecated}
             &rev={rev}
             &type={type}
             &createdBy={createdBy}
             &updatedBy={updatedBy}
             &q={search}
             &sort={sort}
             &aggregations={aggregations}
```

### Parameter description

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
- `{sort}`: String - can be used to sort files based on a payloads' field. This parameter can appear multiple times
  to enable sorting by multiple fields. The default is done by `_createdBy` and `@id`.
- `{aggregations}`: Boolean - if `true` then the response will only contain aggregations of the `@type` and `_project` fields; defaults to `false`. See @ref:[Aggregations](resources-api.md#aggregations).

**Example**

Request
:   @@snip [list.sh](assets/files/list.sh)

Response
:   @@snip [listed.json](assets/files/listed.json)

## Server Sent Events

From Delta 1.5, it is possible to fetch SSEs for all files or just files
in the scope of an organization or a project.

```
GET /v1/files/events                              # for all file events in the application
GET /v1/files/{org_label}/events                  # for file events in the given organization
GET /v1/files/{org_label}/{project_label}/events  # for file events in the given project
```

The caller must have respectively the `events/read` permission on `/`, `{org_label}` and `{org_label}/{project_label}`.

- `{org_label}`: String - the selected organization for which the events are going to be filtered
- `{project_label}`: String - the selected project for which the events are going to be filtered
- `Last-Event-Id`: String - optional HTTP Header that identifies the last consumed resource event. It can be used for
  cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

@@@ note { .warning }

The event type for files SSEs have been changed so that it is easier to distinguish them from other types of resources.

@@@

**Example**

Request
:   @@snip [sse.sh](assets/files/sse.sh)

Response
:   @@snip [sse.json](assets/files/sse.json)
