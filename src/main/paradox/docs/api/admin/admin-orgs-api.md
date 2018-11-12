# Organizations 

Organizations are rooted in the `/v1/orgs` and are used to group and categorize its sub-resources.
An organization it is validated against the [organization schema](https://bluebrain.github.io/nexus/schemas/organization).

Access to resources in the system depends on the access control list set for them. Depending on the access control list, a caller may need to prove its identity by means of an **access token** passed to the `Authorization` header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](../iam/authentication.md) to learn more about how to retrieve an access token.

@@@ note { .tip title="Running examples with Postman" }

The simplest way to explore our API is using [Postman](https://www.getpostman.com/apps). Once downloaded, import the [organizations collection](../assets/organization-postman.json).

If your deployment is protected by an access token: 

Edit the imported collection -> Click on the `Authorization` tab -> Fill the token field.

@@@

## Create an organization

```
PUT /v1/orgs/{label}
  {...}
```

...where `{label}` is the user friendly name assigned to this organization. The semantics of the `label` should be consistent with the type of data provided by its sub-resources, since are exposed on the URI of the sub-resource's operations.

**Example**

Request
:   @@snip [organization.sh](../assets/organization.sh)

Payload
:   @@snip [organization.json](../assets/organization.json)

Response
:   @@snip [organization-ref-new.json](../assets/organization-ref-new.json)


## Update an organization

This operation overrides the payload.

In order to ensure a client does not perform any changes to an organization without having had seen the previous revision of
the organization, the last revision needs to be passed as a query parameter.

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


## Tag an organization

Links an organization revision to a specific name. 

Tagging an organization is considered to be an update as well.

```
PUT /v1/orgs/{label}/tags?rev={previous_rev}
  {
    "tag": "{name}",
    "rev": {rev}
  }
```
... where 

- `{previous_rev}`: Number - is the last known revision for the organization.
- `{label}`: String - is the user friendly name that identifies this organization.
- `{name}`: String - label given to the organization at specific revision.
- `{rev}`: Number - the revision to link the provided `{name}`.

**Example**

Request
:   @@snip [organization-tag.sh](../assets/organization-tag.sh)

Payload
:   @@snip [tag.json](../assets/tag.json)

Response
:   @@snip [organization-ref-tagged.json](../assets/organization-ref-tagged.json)


## Deprecate an organization

Locks the organization, so no further operations can be performed on the resource or on the children resources.

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


## Fetch a resolver (specific tag)

```
GET /v1/orgs/{label}?tag={tag}
```

... where 

- `{tag}`: String - is the tag of the organization to be retrieved.
- `{label}`: String - is the user friendly name that identifies this organization.


**Example**

Request
:   @@snip [organization-fetch-tag.sh](../assets/organization-fetch-tag.sh)

Response
:   @@snip [organization-fetched-tag.json](../assets/organization-fetched-tag.json)


## List organizations

```
GET /v1/orgs?from={from}&size={size}&deprecated={deprecated}&q={full_text_search_query}
```

where...

- `{full_text_search_query}`: String - can be provided to select only the organizations in the collection that have attribute values matching (containing) the provided token; when this field is provided the results will also include score values for each result
- `{from}`: Number - is the parameter that describes the offset for the current query; defaults to `0`
- `{size}`: Number - is the parameter that limits the number of results; defaults to `20`
- `{deprecated}`: Boolean - can be used to filter the resulting organizations based on their deprecation status


**Example**

Request
:   @@snip [organization-list.sh](../assets/organization-list.sh)

Response
:   @@snip [organization-list.json](../assets/organization-list.json)