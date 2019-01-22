# Permissions

Permissions are rooted in the `/v1/permissions` collection.

Each permission is the basic unit to provide a way to limit applications' access to sensitive information.  

Access to resources in the system depends on the access control list set for them. Depending on the access control list, a caller may need to prove its identity by means of an **access token** passed to the `Authorization` header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](./authentication.md) to learn more about how to retrieve an access token.

## Minimum permissions

IAM is configured to include minimum permissions, i.e. permissions that cannot be removed, because they are necessary for correct functioning of Nexus.

Currently the following permissions are required:

-  default permissions for acls, with the exception that everyone should be able to see his own permissions
    - `acls/read`
    - `acls/write`

- default permissions for permissions
    - `permissions/read`
    - `permissions/write`

- default permissions for realms
    - `realms/read`
    - `realms/write`

 - generic permissions for full read access to the global event log
    - `events/read`

-  admin specific permissions
    - `projects/read`
    - `projects/write`
    - `projects/create`
    - `organizations/read`
    - `organizations/write`
    - `organizations/create`

- KG specific permissions
    - `resources/read`
    - `resources/write`
    - `resolvers/read`
    - `resolvers/write`
    - `views/read`
    - `views/write`
    - `schemas/read`
    - `schemas/write`
    - `files/read`
    - `files/write`


## Replace permissions

This operation overrides the collection of permissions.
```
PUT /v1/permissions?rev={previous_rev}
  {...}
```

...where ``{previous_rev}`` is the last known revision number for the permissions.
If there are only minimum permissions present present, this query parameter can be omitted.

The json payload contains the set of permissions to be added.

**Example**

Request
:   @@snip [permissions-replace.sh](../assets/permissions/permissions-replace.sh)

Payload
:   @@snip [permissions-add.json](../assets/permissions/permissions-add.json)

Response
:   @@snip [permissions-replaced-ref.json](../assets/permissions/permissions-replaced-ref.json)


## Subtract permissions

This operation removes the provided permissions from the existing collection of permissions.

```
PATCH /v1/permissions?rev={previous_rev}
  {...}
```
...where ``{previous_rev}`` is the last known revision number for the permissions.

The json payload contains the set of permissions to be deleted.
**Example**

Request
:   @@snip [permissions-subtract.sh](../assets/permissions/permissions-subtract.sh)

Payload
:   @@snip [permissions-subtract.json](../assets/permissions/permissions-subtract.json)

Response
:   @@snip [permissions-subtracted-ref.json](../assets/permissions/permissions-subtracted-ref.json)

## Append permissions

This operation appends the provided permissions to the existing collection of  permissions.

```
PATCH /v1/permissions?rev={previous_rev}
  {...}
```
...where ``{previous_rev}`` is the last known revision number for the permissions.

The json payload contains the set of permissions to be added.

**Example**

Request
:   @@snip [permissions-append.sh](../assets/permissions/permissions-append.sh)

Payload
:   @@snip [permissions-append.json](../assets/permissions/permissions-append.json)

Response
:   @@snip [permissions-appended-ref.json](../assets/permissions/permissions-appended-ref.json)

## Delete all permissions

This operation deletes the all the user defined permission and resets the collection to minimum permissions.

```
DELETE /v1/permissions?rev={previous_rev}
```

...where ``{previous_rev}`` is the last known revision number for the permissions.


Request
:   @@snip [permissions-delete.sh](../assets/permissions/permissions-delete.sh)

Response
:   @@snip [permissions-deleted-ref.json](../assets/permissions/permissions-deleted-ref.json)


## Fetch permissions (latest revision)

```
GET /v1/permissions
```

Request
:   @@snip [permissions-get.sh](../assets/permissions/permissions-get.sh)

Response
:   @@snip [permissions-get.json](../assets/permissions/permissions-get.json)

## Fetch permissions (specific revision)
```
GET /v1/permissions?rev={rev}
```

...where `{rev}` is the revision number of the permissions to be retrieved.

Request
:   @@snip [permissions-get-rev.sh](../assets/permissions/permissions-get-rev.sh)

Response
:   @@snip [permissions-get-rev.json](../assets/permissions/permissions-get-rev.json)