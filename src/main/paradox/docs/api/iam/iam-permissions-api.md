# Permissions

Permissions are rooted in the `/v1/permissions` collection.

Each permission is the basic unit to provide a way to limit applications' access to sensitive information.  

Access to resources in the system depends on the access control list set for them. Depending on the access control list, a caller may need to prove its identity by means of an **access token** passed to the `Authorization` header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](./authentication.md) to learn more about how to retrieve an access token.


## Create permissions

This operation creates the collection of permissions when it is empty.
```
PUT /v1/permissions
  {...}
```

The json payload contains the set of permissions to be added.

**Example**

Request
:   @@snip [permissions-add.sh](../assets/permissions-add.sh)

Payload
:   @@snip [permissions-add.json](../assets/permissions-add.json)

Response
:   @@snip [permissions-added-ref.json](../assets/permissions-added-ref.json)


## Replace permissions

This operation overrides the collection of permissions.
```
PUT /v1/permissions?rev={previous_rev}
  {...}
```

...where ``{previous_rev}`` is the last known revision number for the permissions.
If there are no previous revisions present, this query parameter can be omitted.

The json payload contains the set of permissions to be added.

**Example**

Request
:   @@snip [permissions-replace.sh](../assets/permissions-replace.sh)

Payload
:   @@snip [permissions-add.json](../assets/permissions-add.json)

Response
:   @@snip [permissions-replaced-ref.json](../assets/permissions-replaced-ref.json)


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
:   @@snip [permissions-subtract.sh](../assets/permissions-subtract.sh)

Payload
:   @@snip [permissions-subtract.json](../assets/permissions-subtract.json)

Response
:   @@snip [permissions-subtracted-ref.json](../assets/permissions-subtracted-ref.json)

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
:   @@snip [permissions-append.sh](../assets/permissions-append.sh)

Payload
:   @@snip [permissions-append.json](../assets/permissions-append.json)

Response
:   @@snip [permissions-appended-ref.json](../assets/permissions-appended-ref.json)

## Delete all permissions

This operation deletes the entire collection of permissions.

```
DELETE /v1/permissions?rev={previous_rev}
```

...where ``{previous_rev}`` is the last known revision number for the permissions.


Request
:   @@snip [permissions-delete.sh](../assets/permissions-delete.sh)

Response
:   @@snip [permissions-deleted-ref.json](../assets/permissions-deleted-ref.json)


## Fetch permissions (latest revision)

```
GET /v1/permissions
```

Request
:   @@snip [permissions-get.sh](../assets/permissions-get.sh)

Response
:   @@snip [permissions-get.json](../assets/permissions-get.json)

## Fetch permissions (specific revision)
```
GET /v1/permissions?rev={rev}
```

...where `{rev}` is the revision number of the permissions to be retrieved.

Request
:   @@snip [permissions-get-rev.sh](../assets/permissions-get-rev.sh)

Response
:   @@snip [permissions-get-rev.json](../assets/permissions-get-rev.json)