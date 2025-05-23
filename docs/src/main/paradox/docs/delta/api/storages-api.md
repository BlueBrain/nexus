# Storages

Storages are rooted in the `/v1/storages/{org_label}/{project_label}` collection and are used to describe where
@ref:[files](files-api.md) are physically stored.

Each storage belongs to a `project` identifier by the label `{project_label}` inside an `organization` identifier by the label `{org_label}`.

@@@ note { .tip title="Authorization notes" }

To read or modify storages, the caller must have respectively `storages/read` or `storages/write` permissions on the
current path of the project or its ancestors.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Payload

There are several types (or classes) of storages, that represent different kinds of backends.

### Local disk storage

This is the most basic storage type. It is backed by the local file-system (i.e. where the Nexus deployment is
running) and rooted in an arbitrary path.

Upon project creation, a default disk storage is initialized automatically, so that users can start uploading
resource attachments right away. This resource can be accessed using the @ref:[api mapping](projects-api.md#api-mappings) alias `defaultStorage`.

While typically not necessary, you can manage and create additional disk storages, provided you are aware of the
local file-system structure and that Nexus has read and write access to the target folder.

```json
{
  "@type": "DiskStorage",
  "name": "{name}",
  "description": "{description}",
  "default": "{default}",
  "volume": "{volume}",
  "readPermission": "{read_permission}",
  "writePermission": "{write_permission}",
  "maxFileSize": {max_file_size}
}
```

...where

- `{name}`: String - A name for this storage. This field is optional.
- `{description}`: String - A description for this storage. This field is optional.
- `{default}`: Boolean - the flag to decide whether this storage is going to become the default storage for the target project or not.
- `{volume}`: String - the path to the local file-system volume where files using this storage will be stored. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-volume` (`/tmp`).
- `{read_permission}`: String - the permission a client must have in order to fetch files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-read-permission` (`resources/read`).
- `{write_permission}`: String - the permission a client must have in order to create files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-write-permission` (`files/write`).
- `{max_file_size}`: Long - the maximum allowed size in bytes for files uploaded using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-max-file-size` (10G).

### Amazon S3 compatible storage

This storage type allows the use of an internal or external blob-store that is compatible with the Amazon S3 API.

In order to be able to use this storage, the configuration flag `plugins.storage.storages.amazon.enabled` should be set to `true`.

```json
{
  "@type": "S3Storage",
  "name": "{name}",
  "description": "{description}",
  "bucket": "{name}",
  "default": "{default}",
  "readPermission": "{read_permission}",
  "writePermission": "{write_permission}",
  "maxFileSize": {max_file_size}
}
```

...where

- `{name}`: String - A name for this storage. This field is optional.
- `{description}`: String - A description for this storage. This field is optional.
- `{bucket}`: String -The AWS S3 bucket this storage points to. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-endpoint`.
- `{default}`: Boolean - the flag to decide whether this storage is going to become the default storage for the target project or not.
- `{read_permission}`: String - the permission a client must have in order to fetch files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-read-permission` (`resources/read`).
- `{write_permission}`: String - the permission a client must have in order to create files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-write-permission` (`files/write`).
- `{max_file_size}`: Long - the maximum allowed size in bytes for files uploaded using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-max-file-size` (10G).

## Create using POST

```
POST /v1/storages/{org_label}/{project_label}
  {...}
```

Json payload:

- If an `@id` value is found in the payload, it will be used.
- If an `@id` value is not found in the payload, one will be generated as follows: `base:{UUID}`.
  The `base` is the `prefix` defined on the storage's project (`{project_label}`).

**Example**

Request
:   @@snip [create-post.sh](assets/storages/create-post.sh)

Payload
:   @@snip [create-post.json](assets/storages/create-post.json)

Response
:   @@snip [created-post.json](assets/storages/created-post.json)


## Create using PUT

This alternative endpoint to create a storage is useful in case the json payload does not contain an `@id` but you want
to specify one. The @id will be specified in the last segment of the endpoint URI.

```
PUT /v1/storages/{org_label}/{project_label}/{storage_id}
  {...}
```

Note that if the payload contains an @id different from the `{storage_id}`, the request will fail.

**Example**

Request
:   @@snip [create-put.sh](assets/storages/create-put.sh)

Payload
:   @@snip [create-put.json](assets/storages/create-put.json)

Response
:   @@snip [created-put.json](assets/storages/created-put.json)

## Update

This operation overrides the payload.

In order to ensure a client does not perform any changes to a storage without having had seen the previous revision of
the storage, the last revision needs to be passed as a query parameter.

```
PUT /v1/storages/{org_label}/{project_label}/{storage_id}?rev={previous_rev}
  {...}
```

... where `{previous_rev}` is the last known revision number for the storage.

**Example**

Request
:   @@snip [update.sh](assets/storages/update.sh)

Payload
:   @@snip [update.json](assets/storages/update.json)

Response
:   @@snip [updated.json](assets/storages/updated.json)

## Deprecate

Locks the storage, so no further operations can be performed. It will also not be taken into account by the
default storage selection mechanism.

Deprecating a storage is considered to be an update as well.

```
DELETE /v1/storages/{org_label}/{project_label}/{storage_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the storage.

**Example**

Request
:   @@snip [deprecate.sh](assets/storages/deprecate.sh)

Response
:   @@snip [deprecated.json](assets/storages/deprecated.json)

## Undeprecate

Unlocks the storage, so further operations can be performed. It will again be taken into account by the default storage
selection mechanism.

Undeprecating a storage is considered to be an update as well.

```
PUR /v1/storages/{org_label}/{project_label}/{storage_id}/undeprecate?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the storage.

**Example**

Request
:   @@snip [undeprecate.sh](assets/storages/undeprecate.sh)

Response
:   @@snip [undeprecated.json](assets/storages/undeprecated.json)

## Fetch

```
GET /v1/storages/{org_label}/{project_label}/{storage_id}?rev={rev}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.

**Example**

Request
:   @@snip [fetch.sh](assets/storages/fetch.sh)

Response
:   @@snip [fetched.json](assets/storages/fetched.json)

If the @ref:[redirect to Fusion feature](../../running-nexus/configuration/index.md#fusion-configuration) is enabled and
if the `Accept` header is set to `text/html`, a redirection to the fusion representation of the resource will be returned.

## Fetch original payload

```
GET /v1/storages/{org_label}/{project_label}/{storage_id}/source?rev={rev}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.

**Example**

Request
:   @@snip [fetch-source.sh](assets/storages/fetch-source.sh)

Response
:   @@snip [fetched-source.json](assets/storages/fetched-source.json)

## Server Sent Events

From Delta 1.5, it is possible to fetch SSEs for all storages or just storages
in the scope of an organization or a project.

```
GET /v1/storages/events                               # for all storage events in the application
GET /v1/storages/{org_label}/events                   # for storage events in the given organization
GET /v1/storages/{org_label}/{project_label}/events   # for storage events in the given project
```

The caller must have respectively the `events/read` permission on `/`, `{org_label}` and `{org_label}/{project_label}`.

- `{org_label}`: String - the selected organization for which the events are going to be filtered
- `{project_label}`: String - the selected project for which the events are going to be filtered
- `Last-Event-Id`: String - optional HTTP Header that identifies the last consumed resource event. It can be used for
  cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

@@@ note { .warning }

The event type for storages SSEs have been changed so that it is easier to distinguish them from other types of resources.

@@@

**Example**

Request
:   @@snip [sse.sh](assets/storages/sse.sh)

Response
:   @@snip [sse.json](assets/storages/sse.json)

## Fetch statistics

@@@ note { .warning }

This endpoint is experimental and the response structure might change in the future.

@@@

```
GET /v1/storages/{org_label}/{project_label}/{storage_id}/statistics
```
It returns:

- the number of physical files stored on the storage  (`files`).
- the space used by this file on the given storage (`spaceUsed`).

**Example**

Request
:   @@snip [fetch-statistics.sh](assets/storages/fetch-statistics.sh)

Response
:   @@snip [fetched-statistics.json](assets/storages/fetched-statistics.json)