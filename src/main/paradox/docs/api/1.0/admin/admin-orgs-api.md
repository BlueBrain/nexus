# Organizations 

Organizations are rooted in the `/v1/orgs` path and are used to group and categorize sub-resources.

Access to resources in the system depends on the access control list set for them. A caller may need to prove its identity by means of an **access token** passed in the `Authorization` header (`Authorization: Bearer {token}`).
Please visit @ref:[Authentication](../iam/authentication.md) to learn more about retrieving access tokens.


@@@ note { .tip title="Authorization notes" }	

When  creating organizations, the caller must have `organizations/create` permissions on the current path of the organization or `/`.

When  updating organizations, the caller must have `organizations/write` permissions on the current path of the organization or `/`.

When  reading organizations, the caller must have `organizations/read` permissions on the current path of the organization or `/`.

@@@

## Create an organization

```
PUT /v1/orgs/{label}
  {...}
```

...where `{label}` is the user friendly name assigned to this organization. The semantics of the `label` should be
consistent with the type of data provided by its sub-resources, since it'll be a part of the sub-resources' URI.

**Example**

Request
:   @@snip [organization.sh](../assets/organization.sh)

Payload
:   @@snip [organization.json](../assets/organization.json)

Response
:   @@snip [organization-ref-new.json](../assets/organization-ref-new.json)


## Update an organization

This operation overrides the payload.

In order to ensure a client does not perform any changes to an organization without having had seen the previous
revision of the organization, the last revision needs to be passed as a query parameter.

```
PUT /v1/orgs/{label}?rev={previous_rev}
  {...}
```
... where 

- `{previous_rev}`: Number - is the last known revision for the organization.
- `{label}`: String - is the user friendly name that identifies this organization.

**Example**

Request
:   @@snip [organization-update.sh](../assets/organization-update.sh)

Payload
:   @@snip [organization.json](../assets/organization.json)

Response
:   @@snip [organization-ref-new.json](../assets/organization-ref-updated.json)


## Deprecate an organization

Locks the organization, so that no further operations can be performed on the resource or on the child resources.

Deprecating an organization is considered to be an update as well. 

```
DELETE /v1/orgs/{label}?rev={previous_rev}
```

... where 

- `{label}`: String - is the user friendly name that identifies this organization.
- `{previous_rev}`: Number - is the last known revision for the organization.

**Example**

Request
:   @@snip [organization-deprecate.sh](../assets/organization-deprecate.sh)

Response
:   @@snip [organization-ref-deprecated.json](../assets/organization-ref-deprecated.json)


## Fetch an organization (current version)

```
GET /v1/orgs/{label}
```

...where `{label}` is the user friendly `String` name that identifies this organization.


**Example**

Request
:   @@snip [organization-fetch.sh](../assets/organization-fetch.sh)

Response
:   @@snip [organization-fetched.json](../assets/organization-fetched.json)


## Fetch an organization (specific version)

```
GET /v1/orgs/{label}?rev={rev}
```
... where 

- `{rev}`: Number - is the revision of the organization to be retrieved.
- `{label}`: String - is the user friendly name that identifies this organization.

**Example**

Request
:   @@snip [organization-fetch-revision.sh](../assets/organization-fetch-revision.sh)

Response
:   @@snip [organization-fetched.json](../assets/organization-fetched.json)


## List organizations

```
GET /v1/orgs?from={from}&size={size}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`


**Example**

Request
:   @@snip [organization-list.sh](../assets/organization-list.sh)

Response
:   @@snip [organization-list.json](../assets/organization-list.json)