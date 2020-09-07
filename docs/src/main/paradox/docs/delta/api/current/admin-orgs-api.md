# Organizations 

Organizations are rooted in the `/v1/orgs` path and are used to group and categorize sub-resources.

Access to resources in the system depends on the access control list set for them. A caller may need to prove its 
identity by means of an **access token** passed in the `Authorization` header (`Authorization: Bearer {token}`).
Please visit @ref:[Authentication](authentication.md) to learn more about retrieving access tokens.


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
:   @@snip [organization.sh](assets/organization.sh)

Payload
:   @@snip [organization.json](assets/organization.json)

Response
:   @@snip [organization-ref-new.json](assets/organization-ref-new.json)


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
:   @@snip [organization-update.sh](assets/organization-update.sh)

Payload
:   @@snip [organization.json](assets/organization.json)

Response
:   @@snip [organization-ref-new.json](assets/organization-ref-updated.json)


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
:   @@snip [organization-deprecate.sh](assets/organization-deprecate.sh)

Response
:   @@snip [organization-ref-deprecated.json](assets/organization-ref-deprecated.json)


## Fetch an organization (current version)

```
GET /v1/orgs/{label}
```

...where `{label}` is the user friendly `String` name that identifies this organization.


**Example**

Request
:   @@snip [organization-fetch.sh](assets/organization-fetch.sh)

Response
:   @@snip [organization-fetched.json](assets/organization-fetched.json)


## Fetch an organization (specific version)

```
GET /v1/orgs/{label}?rev={rev}
```
... where 

- `{rev}`: Number - is the revision of the organization to be retrieved.
- `{label}`: String - is the user friendly name that identifies this organization.

**Example**

Request
:   @@snip [organization-fetch-revision.sh](assets/organization-fetch-revision.sh)

Response
:   @@snip [organization-fetched.json](assets/organization-fetched.json)


## List organizations

```
GET /v1/orgs?from={from}&size={size}&deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}&label={label}
```

where...

- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting organizations based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting organizations based on their revision value
- `{type}`: Iri - can be used to filter the resulting organizations based on their `@type` value. This parameter can 
  appear multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting organizations based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting organizations based on the person which performed the last 
  update
- `{label}`: String - can be used to filter the resulting organizations based on its label. E.g.: `label=my` will match 
  any organization's label that contains the string `my`. 

**Example**

Request
:   @@snip [organization-list.sh](assets/organization-list.sh)

Response
:   @@snip [organization-list.json](assets/organization-list.json)


## Organization Server Sent Events

This endpoint allows clients to receive automatic updates from the organizations in a streaming fashion.

```
GET /v1/orgs/events
```

where `Last-Event-Id` is an optional HTTP Header that identifies the last consumed organization event. It can be used 
for cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

The response contains a series of organization events, represented in the following way

```
data:{payload}
event:{type}
id:{id}
```

where...

- `{payload}`: Json - is the actual payload of the current organization
- `{type}`: String - is a type identifier for the current organization. Possible types are: OrganizationCreated, 
  OrganizationUpdated and OrganizationDeprecated
- `{id}`: String - is the identifier of the organization event. It can be used in the `Last-Event-Id` HTTP Header

**Example**

Request
:   @@snip [organization-event.sh](assets/organization-event.sh)

Response
:   @@snip [organization-event.json](assets/organization-event.json)
