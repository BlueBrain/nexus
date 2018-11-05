# Realms

Realms are rooted in `/v1/realms` collection.

Each realm defines a specific authentication provider.
Any of the authentication providers can be used to obtain access tokens that can be used with Nexus.



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

The `requiredScopes` field defines the scopes that need to be included in the token from this realm in order for Nexus to accept it as valid.



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
 :   @@snip [realms-get.sh](../assets/realms/realms-get.sh)

Response
 :   @@snip [realms-get.json](../assets/realms/realms-get.json)