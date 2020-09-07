# Storages

Storages are rooted in the `/v1/storages/{org_label}/{project_label}` collection and are used to describe where
@ref:[files](kg-files-api.md) are physically stored.

Each storage...

- belongs to a `project` identifier by the label `{project_label}`
- inside an `organization` identifier by the label `{org_label}`
- is validated against the [storage schema](https://bluebrainnexus.io/schemas/storage.json).

Access to resources in the system depends on the access control list set for them. Depending on the access control list,
a caller may need to prove its identity by means of an **access token** passed to the `Authorization` header
(`Authorization: Bearer {token}`). Please visit @ref:[Authentication](authentication.md) to learn how to retrieve an 
access token.

@@@ note { .tip title="Authorization notes" }

To read or modify storages, the caller must have respectively `storages/read` or `storages/write` permissions on the
current path of the project or its ancestors.

@@@

## Storage types

There are several types (or classes) of storages, that represent different kinds of backends.

### Local disk storage

This is the most basic storage type. It is backed by the local file-system (i.e. where the Nexus deployment is
running) and rooted in an arbitrary path.

Upon project creation, a default disk storage is initialized automatically, so that users can start uploading
resource attachments right away. This resource has the @id `nxv:diskStorageDefault`.

Its behavior is similar to earlier versions of the Nexus API: files are stored and managed by the system in an opaque,
internal way.

While typically not necessary, you can manage and create additional disk storages, provided you are aware of the
local file-system structure and that Nexus has read and write access to the target folder.

### Remote disk storage

This storage type relies on a remote HTTP service that exposes basic file operations on an underlying POSIX file-system.
This is particularly handy if your organization is running some kind of distributed network storage (such as Ceph,
Gluster, GPFS, Lustre, ...) that you don't want to mount directly on the system where Nexus runs.

While there's no formal specification for this service, you can check out or deploy our own implementation:
@link:[Nexus remote storage service](https://github.com/BlueBrain/nexus-storage){ open=new }.

### Amazon S3 compatible storage

This storage type allows the use of an internal or external blob-store that is compatible with the Amazon S3 API.

### Changing the default storage

The internal resource describing every storage has a boolean field called `default`. The selection mechanism when
no storage id is provided picks the **last created** storage with `default` set to `true`.

### Resource format

These tables summarize mandatory and optional fields for each storage type:

| @type               | @id      | default   | volume    | readPermission | writePermission | maxFileSize |
|---------------------|----------|-----------|-----------|----------------|-----------------|-------------|
| DiskStorage         | optional | mandatory | mandatory | optional       | optional        | optional    |

| @type               | @id      | default   | folder    | endpoint | credentials | readPermission | writePermission | maxFileSize |
|---------------------|----------|-----------|-----------|----------|-------------|----------------|-----------------|-------------|
| RemoteDiskStorage   | optional | mandatory | mandatory | optional | optional    | optional       | optional        | optional    |

| @type               | @id      | default   | bucket    | endpoint  | accessKey | secretKey | readPermission | writePermission | maxFileSize |
|---------------------|----------|-----------|-----------|-----------|-----------|-----------|----------------|-----------------|-------------|
| S3Storage           | optional | mandatory | mandatory | optional  | optional  | optional  | optional       | optional        | optional    |

@@@ note { .warning }

The endpoint field is optional for the S3 storage type, as our internal implementation uses the official S3 client
and defaults to `s3.amazonaws.com`. Relying on this behavior is discouraged.

@@@

## Create a storage using POST

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
:   @@snip [storage-post.sh](assets/storages/storage-post.sh)

Payload
:   @@snip [storage-post.json](assets/storages/storage-post.json)

Response
:   @@snip [storage-ref-new.json](assets/storages/storage-ref-new.json)


## Create a storage using PUT

This alternative endpoint to create a storage is useful in case the json payload does not contain an `@id` but you want
to specify one. The @id will be specified in the last segment of the endpoint URI.

```
PUT /v1/storages/{org_label}/{project_label}/{storage_id}
  {...}
```

Note that if the payload contains an @id different from the `{storage_id}`, the request will fail.

**Example**

Request
:   @@snip [storage-put.sh](assets/storages/storage-put.sh)

Payload
:   @@snip [storage-put.json](assets/storages/storage-put.json)

Response
:   @@snip [storage-ref-new.json](assets/storages/storage-ref-new.json)

## Update a storage

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
:   @@snip [storage-update.sh](assets/storages/storage-update.sh)

Payload
:   @@snip [storage.json](assets/storages/storage-update.json)

Response
:   @@snip [storage-ref-updated.json](assets/storages/storage-ref-updated.json)

## Tag a storage

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
:   @@snip [storage-tag.sh](assets/storages/storage-tag.sh)

Payload
:   @@snip [tag.json](assets/tag.json)

Response
:   @@snip [storage-ref-tagged.json](assets/storages/storage-ref-tagged.json)

## Deprecate a storage

Locks the storage, so no further operations can be performed. It will also not be taken into account by the
default storage selection mechanism.

Deprecating a storage is considered to be an update as well.

```
DELETE /v1/storages/{org_label}/{project_label}/{storage_id}?rev={previous_rev}
```

... where `{previous_rev}` is the last known revision number for the storage.

**Example**

Request
:   @@snip [storage-deprecate.sh](assets/storages/storage-deprecate.sh)

Response
:   @@snip [storage-ref-deprecated.json](assets/storages/storage-ref-deprecated.json)

## Fetch a storage

When fetching a storage, the response format can be chosen through HTTP content negotiation, using the **Accept** HTTP header.

- **application/ld+json**: JSON-LD output response. Further specifying the query parameter `format=compacted|expanded` 
  will provide with the JSON-LD @link:[compacted document form](https://www.w3.org/TR/json-ld11/#compacted-document-form){ open=new } 
  or the @link:[expanded document form](https://www.w3.org/TR/json-ld11/#expanded-document-form){ open=new }.
- **application/n-triples**: RDF n-triples response, as defined by the @link:[w3](https://www.w3.org/TR/n-triples/){ open=new }.
- **text/vnd.graphviz**: A @link:[DOT response](https://www.graphviz.org/doc/info/lang.html){ open=new }.

If `Accept: */*` HTTP header is present, Nexus defaults to the JSON-LD output in compacted form.

```
GET /v1/storages/{org_label}/{project_label}/{storage_id}?rev={rev}&tag={tag}
```
where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [storage-fetch.sh](assets/storages/storage-fetch.sh)

Response
:   @@snip [storage-fetched.json](assets/storages/storage-fetched.json)


## Fetch a storage original payload

```
GET /v1/storages/{org_label}/{project_label}/{storage_id}/source?rev={rev}&tag={tag}
```

where ...

- `{rev}`: Number - the targeted revision to be fetched. This field is optional and defaults to the latest revision.
- `{tag}`: String - the targeted tag to be fetched. This field is optional.

`{rev}` and `{tag}` fields cannot be simultaneously present.

**Example**

Request
:   @@snip [storage-fetch.sh](assets/storages/storage-fetch-source.sh)

Response
:   @@snip [storage-fetched.json](assets/storages/storage-fetched-source.json)

## List storages

```
GET /v1/storages/{org_label}/{project_label}?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&q={search}&sort={sort}
```

where...

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
  to enable sorting by multiple fields

**Example**

Request
:   @@snip [storage-list.sh](assets/storages/storage-list.sh)

Response
:   @@snip [storage-list.json](assets/storages/storage-list.json)
