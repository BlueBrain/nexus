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

## Payload

```json
{
  "description": "{description}"
}
```
...where `{description}` as an optional `String` providing some descriptive information about the organization.

## Create

```
PUT /v1/orgs/{label}
  {...}
```

...where `{label}` is the user friendly name assigned to this organization. The semantics of the `label` should be
consistent with the type of data provided by its sub-resources, since it'll be a part of the sub-resources' URI.

**Example**

Request
:   @@snip [create.sh](assets/organizations/create.sh)

Response
:   @@snip [created.json](assets/organizations/created.json)


## Update

This operation overrides the organization payload (description field).

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
:   @@snip [update.sh](assets/organizations/update.sh)

Response
:   @@snip [updated.json](assets/organizations/updated.json)

## Deprecate

Locks the organization, so that no further operations can be performed on the organization or on the child resources.

Deprecating an organization is considered to be an update as well. 

```
DELETE /v1/orgs/{label}?rev={previous_rev}
```

... where 

- `{label}`: String - is the user friendly name that identifies this organization.
- `{previous_rev}`: Number - is the last known revision for the organization.

**Example**

Request
:   @@snip [deprecate.sh](assets/organizations/deprecate.sh)

Response
:   @@snip [deprecated.json](assets/organizations/deprecated.json)


## Fetch (current version)

```
GET /v1/orgs/{label}
```

...where `{label}` is the user friendly `String` name that identifies this organization.

**Example**

Request
:   @@snip [fetch.sh](assets/organizations/fetch.sh)

Response
:   @@snip [fetched.json](assets/organizations/fetched.json)

## Fetch (specific version)

```
GET /v1/orgs/{label}?rev={rev}
```
... where 

- `{rev}`: Number - is the revision of the organization to be retrieved.
- `{label}`: String - is the user friendly name that identifies this organization.

**Example**

Request
:   @@snip [fetchAt.sh](assets/organizations/fetchAt.sh)

Response
:   @@snip [fetched.json](assets/organizations/fetched.json)


## List

```
GET /v1/orgs?from={from}
             &size={size}
             &deprecated={deprecated}
             &rev={rev}
             &createdBy={createdBy}
             &updatedBy={updatedBy}
             &label={label}
             &sort={sort}
```

where...

- `{from}`: Number - the offset from which to start the listings. Defaults to `0`
- `{size}`: Number - the maximum amount fo results to be returned. Defaults to `30`
- `{deprecated}`: Boolean - filter the resulting organizations based on their deprecation status. Optional parameter.
- `{rev}`: Number - filter the resulting organizations based on their revision value. Optional parameter.
- `{createdBy}`: Iri - filter the resulting organizations based on their creator. Optional parameter.
- `{updatedBy}`: Iri - filter the resulting organizations based on the person which performed the last update. Optional parameter.
- `{label}`: String - filter the resulting organizations based on its label. E.g.: `label=my` will match 
  any organization's label that contains the string `my`. Optional parameter.
- `{sort}`: String - orders the resulting organizations based on its metadata fields.  Optional parameter that can appear multiple times, further specifying the ordering criteria. Defaults to `_createdAt`, ordering organizations by creation date.


**Example**

Request
:   @@snip [list.sh](assets/organizations/list.sh)

Response
:   @@snip [listed.json](assets/organizations/listed.json)


## Server Sent Events

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

- `{payload}`: Json - is the actual payload of the current organization event
- `{type}`: String - is a type identifier for the current organization. Possible types are: OrganizationCreated, 
  OrganizationUpdated and OrganizationDeprecated
- `{id}`: String - is the identifier of the organization event. It can be used in the `Last-Event-Id` HTTP Header

**Example**

Request
:   @@snip [sse.sh](assets/organizations/sse.sh)

Response
:   @@snip [sse.json](assets/organizations/sse.json)
