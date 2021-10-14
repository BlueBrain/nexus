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
  "default": "{default}",
  "volume": "{volume}",
  "readPermission": "{read_permission}",
  "writePermission": "{write_permission}",
  "capacity": "{capacity}",
  "maxFileSize": {max_file_size}
}
```

...where

- `{default}`: Boolean - the flag to decide whether this storage is going to become the default storage for the target project or not.
- `{volume}`: String - the path to the local file-system volume where files using this storage will be stored. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-volume` (`/tmp`).
- `{read_permission}`: String - the permission a client must have in order to fetch files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-read-permission` (`resources/read`).
- `{write_permission}`: String - the permission a client must have in order to create files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-write-permission` (`files/write`).
- `{capacity}`: Long - the maximum allocated capacity in bytes for storing files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-capacity` (None).
- `{max_file_size}`: Long - the maximum allowed size in bytes for files uploaded using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.disk.default-max-file-size` (10G).

### Remote disk storage

@@@ warning
The Remote disk storage and it remote service implementation are now deprecated and will be removed in an upcoming release.
@@@

This storage type relies on a remote HTTP service that exposes basic file operations on an underlying POSIX file-system.
This is particularly handy if your organization is running some kind of distributed network storage (such as Ceph,
Gluster, GPFS, Lustre, ...) that you don't want to mount directly on the system where Nexus Delta runs.

While there's no formal specification for this service, you can check out or deploy our own implementation:
@link:[Nexus remote storage service](https://github.com/BlueBrain/nexus/tree/$git.branch$/storage){ open=new }.

In order to be able to use this storage, the configuration flag `plugins.storage.storages.remote-disk.enabled` should be set to `true`.

```json
{
  "@type": "RemoteDiskStorage",
  "default": "{default}",
  "endpoint": "{endpoint}",
  "credentials": "{credentials}",
  "folder": "{folder}",
  "readPermission": "{read_permission}",
  "writePermission": "{write_permission}",
  "maxFileSize": {max_file_size}
}
```

...where

- `{default}`: Boolean - the flag to decide whether this storage is going to become the default storage for the target project or not.
- `{endpoint}`: Uri - the endpoint where the storage service is listening to requests. This field is optional, defaulting to the configuration flag `plugins.storage.storages.remote-disk.default-endpoint`.
- `{credentials}`: String - the service account access token to authenticate and authorize Nexus Delta client against the storage service. This field is optional, defaulting to the configuration flag `plugins.storage.storages.remote-disk.default-credentials`.
- `{folder}`: String - the storage service bucket where files using this storage are going to be saved.
- `{read_permission}`: String - the permission a client must have in order to fetch files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.remote-disk.default-read-permission` (`resources/read`).
- `{write_permission}`: String - the permission a client must have in order to create files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.remote-disk.default-write-permission` (`files/write`).
- `{max_file_size}`: Long - the maximum allowed size in bytes for files uploaded using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.remote-disk.default-max-file-size` (10G).

### Amazon S3 compatible storage

This storage type allows the use of an internal or external blob-store that is compatible with the Amazon S3 API.

In order to be able to use this storage, the configuration flag `plugins.storage.storages.amazon.enabled` should be set to `true`.

```json
{
  "@type": "S3Storage",
  "default": "{default}",
  "endpoint": "{endpoint}",
  "accessKey": "{access_key}",
  "secretKey": "{secret_key}",
  "region": "{region}",
  "readPermission": "{read_permission}",
  "writePermission": "{write_permission}",
  "maxFileSize": {max_file_size}
}
```

...where

- `{default}`: Boolean - the flag to decide whether this storage is going to become the default storage for the target project or not.
- `{endpoint}`: Uri - the Amazon S3 compatible service endpoint. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-endpoint`.
- `{access_key}`: String - the Amazon S3 compatible access key. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-access-key`.
- `{secret_key}`: String - the Amazon S3 compatible secret key. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-secret-key`.
- `{region}`: String - the Amazon S3 compatible region. This field is optional, defaulting to the S3 default region configuration.
- `{read_permission}`: String - the permission a client must have in order to fetch files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-read-permission` (`resources/read`).
- `{write_permission}`: String - the permission a client must have in order to create files using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-write-permission` (`files/write`).
- `{max_file_size}`: Long - the maximum allowed size in bytes for files uploaded using this storage. This field is optional, defaulting to the configuration flag `plugins.storage.storages.amazon.default-max-file-size` (10G).

## Indexing

All the API calls modifying a storage (creation, update, tagging, deprecation) can specify whether the storage should be indexed
synchronously or in the background. This behaviour is controlled using `indexing` query param, which can be one of two values:

- `async` - (default value) the storage will be indexed asynchronously
- `sync` - the storage will be indexed synchronously and the API call won't return until the indexing is finished

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

## Tag

Links a storage revision to a specific name.

Tagging a storage is considered to be an update as well.

```
POST /v1/storages/{org_label}/{project_label}/{storage_id}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```

... where

- `{previous_rev}`: Number - the last known revision for the storage.
- `{name}`: String - label given to the storage at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [tag.sh](assets/storages/tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [tagged.json](assets/storages/tagged.json)

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

## Fetch

```
GET /v1/storages/{org_label}/{project_label}/{storage_id}?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch.sh](assets/storages/fetch.sh)

Response
:   @@snip [fetched.json](assets/storages/fetched.json)


## Fetch original payload

```
GET /v1/storages/{org_label}/{project_label}/{storage_id}/source?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch-source.sh](assets/storages/fetch-source.sh)

Response
:   @@snip [fetched-source.json](assets/storages/fetched-source.json)

## Fetch tags

Retrieves all the tags available for the `{storage_id}`.

```
GET /v1/storages/{org_label}/{project_label}/{storage_id}/tags?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision of the tags to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag of the tags to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [fetch-tags.sh](assets/storages/fetch-tags.sh)

Response
:   @@snip [fetched-tags.json](assets/tags.json)


## List

There are three available endpoints to list storages in different scopes.

### Within a project

```
GET /v1/storages/{org_label}/{project_label}?from={from}
                                             &size={size}
                                             &deprecated={deprecated}
                                             &rev={rev}
                                             &type={type}
                                             &createdBy={createdBy}
                                             &updatedBy={updatedBy}
                                             &q={search}
                                             &sort={sort}
```

### Within an organization

This operation returns only storages from projects defined in the organisation `{org_label}` and where the caller has the `resources/read` permission.

```
GET /v1/storages/{org_label}?from={from}
                            &size={size}
                            &deprecated={deprecated}
                            &rev={rev}
                            &type={type}
                            &createdBy={createdBy}
                            &updatedBy={updatedBy}
                            &q={search}
                            &sort={sort}
```

### Within all projects

This operation returns only storages from projects where the caller has the `resources/read` permission.

```
GET /v1/storages?from={from}
                &size={size}
                &deprecated={deprecated}
                &rev={rev}
                &type={type}
                &createdBy={createdBy}
                &updatedBy={updatedBy}
                &q={search}
                &sort={sort}
```

### Parameter description

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting storages based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting storages based on their revision value
- `{type}`: Iri - can be used to filter the resulting storages based on their `@type` value. This parameter can appear
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting storages based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting storages based on the person which performed the last update
- `{search}`: String - can be provided to select only the storages in the collection that have attribute values matching
  (containing) the provided string
- `{sort}`: String - can be used to sort storages based on a payloads' field. This parameter can appear multiple times
  to enable sorting by multiple fields. The default is done by `_createdBy` and `@id`.


**Example**

Request
:   @@snip [list.sh](assets/storages/list.sh)

Response
:   @@snip [listed.json](assets/storages/listed.json)


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

- the instant of the latest consumed event (`lastProcessedEventDateTime`).
- the number of physical files stored on the storage  (`files`).
- the space used by this file on the given storage (`spaceUsed`).

**Example**

Request
:   @@snip [fetch-statistics.sh](assets/storages/fetch-statistics.sh)

Response
:   @@snip [fetched-statistics.json](assets/storages/fetched-statistics.json)