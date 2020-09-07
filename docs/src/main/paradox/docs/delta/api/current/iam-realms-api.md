# Realms

Realms are rooted in `/v1/realms` collection.

Each realm defines a specific authentication provider.
Any of the authentication providers can be used to obtain access tokens that can be used with Nexus.

Access to resources in the system depends on the access control list set for them. Depending on the access control 
list, a caller may need to prove its identity by means of an **access token** passed to the `Authorization` 
header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](authentication.md) to learn more about 
how to retrieve an access token.

@@@ note { .tip title="Authorization notes" }	

When  modifying realms, the caller must have `realms/write` permissions on the path `/`.

When  reading realms, the caller must have `realms/read` permissions on the path `/`.

@@@

## Create a realm
 This operation creates a realm.

```
PUT /v1/realms/{realm}
```


The following examples describe the payload used to create a realm.

**Example**

Request
:   @@snip [realm-add.sh](assets/realms/realm-add.sh)

Payload
:   @@snip [realm-add.json](assets/realms/realm-add.json)

Response
:   @@snip [realm-added-ref.json](assets/realms/realm-added-ref.json)

The `logo` parameter is optional.



## Update a realm
 This operation updates a realm.
```
PUT /v1/realms/{realm}?rev={previous_rev}
  {...}
```

 where ``{previous_rev}`` is the last known revision number for the realm.
 The json payload should be the same as the one used to create realms.

 **Example**

Request
 :   @@snip [realm-replace.sh](assets/realms/realm-replace.sh)

Payload
 :   @@snip [realm-replace.json](assets/realms/realm-replace.json)

Response
 :   @@snip [realm-replaced-ref.json](assets/realms/realm-replaced-ref.json)


## Deprecate a realm

This operation deprecates a realm. After deprecation, realms loose their metadata

  ```
 DELETE /v1/realms/{realm}?rev={previous_rev}
   {...}
 ```

  where `{previous_rev}` is the last known revision number for the realm.

Request
 :   @@snip [realm-delete.sh](assets/realms/realm-delete.sh)

Response
 :   @@snip [realm-deleted-ref.json](assets/realms/realm-deleted-ref.json)


## List realms

 Lists all available realms.

```
 GET /v1/realms?deprecated={deprecated}&rev={rev}&type={type}&createdBy={createdBy}&updatedBy={updatedBy}
```
where...

- `{deprecated}`: Boolean - can be used to filter the resulting realms based on their deprecation status
- `{rev}`: Number - can be used to filter the resulting realms based on their revision value
- `{type}`: Iri - can be used to filter the resulting realms based on their `@type` value. This parameter can appear 
  multiple times, filtering further the `@type` value.
- `{createdBy}`: Iri - can be used to filter the resulting realms based on their creator
- `{updatedBy}`: Iri - can be used to filter the resulting realms based on the person which performed the last update

Request
 :   @@snip [realms-list.sh](assets/realms/realms-list.sh)

Response
 :   @@snip [realms-list.json](assets/realms/realms-list.json)

## Fetch a realm (current version)

```
GET /v1/realms/{realm}
```

**Example**

Request
:   @@snip [realm-fetch.sh](assets/realms/realm-fetch.sh)

Response
:   @@snip [realm-fetch.json](assets/realms/realm-fetch.json)


## Fetch a realm (specific version)

```
GET /v1/realms/{realm}?rev={rev}
```
... where `{rev}` is the revision number of the resolver to be retrieved.

**Example**

Request
:   @@snip [realm-fetch-revision.sh](assets/realms/realm-fetch-revision.sh)

Response
:   @@snip [realm-fetch.json](assets/realms/realm-fetch.json)


## Realms Server Sent Events

This endpoint allows clients to receive automatic updates from the realms in a streaming fashion.

```
GET /v1/realms/events
```

where `Last-Event-Id` is an optional HTTP Header that identifies the last consumed realm event. It can be used for 
cases when a client does not want to retrieve the whole event stream, but to start after a specific event.

The response contains a series of realm events, represented in the following way

```
data:{payload}
event:{type}
id:{id}
```

where...

- `{payload}`: Json - is the actual payload of the current realm
- `{type}`: String - is a type identifier for the current realm. Possible types are: RealmCreated, RealmUpdated and 
  RealmDeprecated
- `{id}`: String - is the identifier of the realm event. It can be used in the `Last-Event-Id` HTTP Header

**Example**

Request
:   @@snip [realm-event.sh](assets/realms/event.sh)

Response
:   @@snip [realm-event.json](assets/realms/event.json)