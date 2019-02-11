# Realms

Realms are rooted in `/v1/realms` collection.

Each realm defines a specific authentication provider.
Any of the authentication providers can be used to obtain access tokens that can be used with Nexus.

Access to resources in the system depends on the access control list set for them. Depending on the access control list, a caller may need to prove its identity by means of an **access token** passed to the `Authorization` header (`Authorization: Bearer {token}`). Please visit @ref:[Authentication](./authentication.md) to learn more about how to retrieve an access token.

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
:   @@snip [realm-add.sh](../assets/realms/realm-add.sh)

Payload
:   @@snip [realm-add.json](../assets/realms/realm-add.json)

Response
:   @@snip [realm-added-ref.json](../assets/realms/realm-added-ref.json)

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
 :   @@snip [realm-replace.sh](../assets/realms/realm-replace.sh)

Payload
 :   @@snip [realm-replace.json](../assets/realms/realm-replace.json)

Response
 :   @@snip [realm-replaced-ref.json](../assets/realms/realm-replaced-ref.json)


## Delete a realm
  This operation deletes a realm.

  ```
 DELETE /v1/realms/{realm}?rev={previous_rev}
   {...}
 ```

  where `{previous_rev}` is the last known revision number for the realm.

Request
 :   @@snip [realm-delete.sh](../assets/realms/realm-delete.sh)

Response
 :   @@snip [realm-deleted-ref.json](../assets/realms/realm-deleted-ref.json)


## List realms

 Lists all available realms.

```
 GET /v1/realms
```

Request
 :   @@snip [realms-list.sh](../assets/realms/realms-list.sh)

Response
 :   @@snip [realms-list.json](../assets/realms/realms-list.json)

## Fetch a realm (current version)

```
GET /v1/realms/{realm}
```

**Example**

Request
:   @@snip [realm-fetch.sh](../assets/realms/realm-fetch.sh)

Response
:   @@snip [realm-fetch.json](../assets/realms/realm-fetch.json)


## Fetch a realm (specific version)

```
GET /v1/realms/{realm}?rev={rev}
```
... where `{rev}` is the revision number of the resolver to be retrieved.

**Example**

Request
:   @@snip [realm-fetch-revision.sh](../assets/realms/realm-fetch-revision.sh)

Response
:   @@snip [realm-fetch.json](../assets/realms/realm-fetch.json)
