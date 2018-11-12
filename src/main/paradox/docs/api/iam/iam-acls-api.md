# Access Control Lists

Access Control Lists are rooted in the `/v1/acls` collection.

An ACL defines the applications' data access restriction using the following three parameters:
         
- permission: the value used to limit application's access to information.
- identity: a client identity reference, e.g. a certain user, a group, an anonymous user or someone who is authenticated to a certain realm.
- path: the address where to apply the restrictions. Examples of paths are: `/`, `/myorg` or `/myorg/myproject`

Access to resources in the system depends on the access control list set for them. Depending on the access control list, a caller may need to prove its identity by means of an **access token** passed to the `Authorization` header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](./authentication.md) to learn more about how to retrieve an access token.

## ACLs Hierarchy

It is important to know that ACLs are represented in a tree-like structure depending on their path. Imagine the following scenario:
![Resources tree](../assets/permissions-tree.png "Permissions tree")

Each block is identified by a path that contains a list of permissions for a certain identity (identities are color code divided). There is a special set of permissions which restrict the use of the ACLs API

- **acls/read** - an auth. token containing an identity with this permission is allowed to fetch a collection of ACL from any other identity.
- **acls/write** - an auth. token containing an identity with this permission is allowed to perform the call to the following endpoints: [cerate ACLs](#create-acls), [replace ACLs](#replace-acls), [subtract ACLs](#subtract-acls), [append ACLs](#append-acls) and [delete ACLs](#delete-acls).

Those permissions need to be present in the current `{address}` where the API interaction occurs or in any parent address. In other words, they are inherited.

Let's clarify this concept with an example from the previous diagram. `identity 1` could call the [create ACLs](#create-acls) endpoint on any `{address}` while `identity 2` could only call the same endpoint for any address child of `/myorg` (like `/myorg/myproj`). At the same time, `identity 3` could not perform any of the write operations.

## Create ACLs

This operation creates a collection of ACL on the provided path.
```
PUT /v1/acls/{address}
  {...}
```
...where `{address}` is the target path for the ACL collection.

The json payload contains the collection of ACL to set.

**Example**

Request
:   @@snip [acls-add.sh](../assets/acls-add.sh)

Payload
:   @@snip [acls-add.json](../assets/acls-add.json)

Response
:   @@snip [acls-added-ref.json](../assets/acls-added-ref.json)


## Replace ACLs

This operation overrides the collection of ACL on the provided path.
```
PUT /v1/acls/{address}?rev={previous_rev}
  {...}
```

...where:

- `{previous_rev}`: Number - the last known revision for the ACL collection.
- `{address}`: String - is the target path for the ACL collection.

The json payload contains the collection of ACL to set.

**Example**

Request
:   @@snip [acls-replace.sh](../assets/acls-replace.sh)

Payload
:   @@snip [acls-add.json](../assets/acls-add.json)

Response
:   @@snip [acls-replaced-ref.json](../assets/acls-replaced-ref.json)


## Subtract ACLs

This operation removes the provided ACL collection from the existing collection of ACL on the provided path.

```
PATCH /v1/acls/{address}?rev={previous_rev}
  {...}
```
...where:

- `{previous_rev}`: Number - the last known revision for the ACL collection.
- `{address}`: String - is the target path for the ACL collection.
 
The json payload contains the collection of ACL to remove.

**Example**

Request
:   @@snip [acls-subtract.sh](../assets/acls-subtract.sh)

Payload
:   @@snip [acls-subtract.json](../assets/acls-subtract.json)

Response
:   @@snip [acls-subtracted-ref.json](../assets/acls-subtracted-ref.json)

## Append ACLs

This operation appends the provided ACL collection to the existing collection of ACL on the provided path.

```
PATCH /v1/acls/{address}?rev={previous_rev}
  {...}
```
...where:

- `{previous_rev}`: Number - the last known revision for the ACL collection.
- `{address}`: String - is the target path for the ACL collection.

The json payload contains the collection of ACL to add.

**Example**

Request
:   @@snip [acls-append.sh](../assets/acls-append.sh)

Payload
:   @@snip [acls-append.json](../assets/acls-append.json)

Response
:   @@snip [acls-appended-ref.json](../assets/acls-appended-ref.json)


## Delete ACLs

This operation deletes the entire collection of ACL on the provided path.

```
DELETE /v1/acls/{address}?rev={previous_rev}
```

...where:
 
- `{previous_rev}`: Number - the last known revision for the ACL collection.
- `{address}`: String - is the target path for the ACL collection.

Request
:   @@snip [acls-delete.sh](../assets/acls-delete.sh)

Response
:   @@snip [acls-deleted-ref.json](../assets/acls-deleted-ref.json)


## Fetch ACLs

```
GET /v1/acls/{address}?rev={rev}&self={self}
```

...where 

- `{address}`: String - is the target path for the ACL collection.
- `{rev}`: Number - the revision of the ACL to be retrieved. This parameter is optional and it defaults to the current revision.
- `{self}`: Boolean - if `true`, only the ACLs containing the identities found on the auth. token are included in the response. If `false` all the ACLs on the current `{address}` are included. This parameter is optional and it defaults to `true`.

The ability to use the query parameter `self=false` depends on whether or not any of the identities found on the auth. token contains the `acls:read` permission on the provided `{address}` or its parents. For further details, check [ACLs hierarchy](#acls-hierarchy).

Request
:   @@snip [acls-get.sh](../assets/acls-get.sh)

Response
:   @@snip [acls-fetched.json](../assets/acls-fetched.json)


## List ACLs

```
GET /v1/acls/{address}?ancestors={ancestors}&self={self}
```

...where 

- `{address}`: String - is the target path for the ACL collection.
- `{ancestors}`: Boolean - if `true`, the ACLs of the parent `{address}` are included in the response. If `false` only the ACLs on the current `{address}` are included. This parameter is optional and it defaults to `false`.
- `{self}`: Boolean - if `true`, only the ACLs containing the identities found on the auth. token are included in the response. If `false` all the ACLs on the current `{address}` are included. This parameter is optional and it defaults to `true`.

The ability to use the query parameter `self=false` and `ancestors=true` depends on whether or not any of the identities found on the auth. token contains the `acls:read` permission on the provided `{address}` or its parents. For further details, check [ACLs hierarchy](#acls-hierarchy).

The `{address}` can contain the special character `*` which can be read as `any`. 

Let's imagine we have the ACLs from the [following diagram in place](#acls-hierarchy). If we query this endpoint with the address `/myorg/*`, we are selecting the ACLs defined in `/myorg/myproj` and `myorg/myproj2`. Likewise If we use the address `/*`, we are selecting the ACLs defined in `/myorg` and `myorg2`.

The following examples illustrate listings from the diagram on the section [ACLs hierarchy](#acls-hierarchy) with the following considerations:

- identity 1: Is a group called `one`
- identity 2: Is a group called `two`
- identity 3: Is a user called `me`
- The auth. token is linked to the `identity 1`.

Request
:   @@snip [acls-list.sh](../assets/acls-list.sh)

Response
:   @@snip [acls-listed.json](../assets/acls-listed.json)

Request (with ancestors)
:   @@snip [acls-list-ancestors.sh](../assets/acls-list-ancestors.sh)

Response (with ancestors)
:   @@snip [acls-listed-ancestors.json](../assets/acls-listed-ancestors.json)
