# Realms

Realms are rooted in `/v1/realms` collection.

Each realm defines a specific authentication provider.
Any of the authentication providers can be used to obtain access tokens that can be used with Nexus.

@@@ note { .tip title="Authorization notes" }	

When  modifying realms, the caller must have `realms/write` permissions on the path `/`.

When  reading realms, the caller must have `realms/read` permissions on the path `/`.

Please visit @ref:[Authentication & authorization](authentication.md) section to learn more about it.

@@@

## Payload

```
{
  "name": "{name}",
  "openIdConfig": "{openIdConfig}",
  "logo": "{logo}",
  "acceptedAudiences": {acceptedAudiences}
}
```

where...

- `{name}`: String - the realm name.
- `{openIdConfig}`: IRI - the provider @link:[OpenID configuration](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest){ open=new }.
- `{logo}`: IRI - the logo Url for the realm. This field is optional.
- `{acceptedAudiences}`: Array[String] - ar array of accepted @link:[audiences](https://datatracker.ietf.org/doc/html/rfc7519#section-4.1.3) { open=new } as string values. If provided, the token should have the `aud` with some of its values matching some of the `acceptedAudience` values on the realm. This field is optional.

## Create
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


## Update
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


## Deprecate

This operation deprecates a realm.

  ```
 DELETE /v1/realms/{realm}?rev={previous_rev}
 ```

  where `{previous_rev}` is the last known revision number for the realm.

Request
 :   @@snip [realm-delete.sh](assets/realms/realm-delete.sh)

Response
 :   @@snip [realm-deleted-ref.json](assets/realms/realm-deleted-ref.json)


## List

 Lists all available realms.

```
 GET /v1/realms?from={from}
                 &size={size}
                 &deprecated={deprecated}
                 &rev={rev}
                 &createdBy={createdBy}
                 &updatedBy={updatedBy}
                 &sort={sort}
```
where...

- `{from}`: Number - the offset from which to start the listings. Defaults to `0`
- `{size}`: Number - the maximum amount fo results to be returned. Defaults to `30`
- `{deprecated}`: Boolean - filter the resulting realms based on their deprecation status. Optional parameter.
- `{rev}`: Number - filter the resulting realms based on their revision value. Optional parameter.
- `{createdBy}`: Iri - filter the resulting realms based on their creator. Optional parameter.
- `{updatedBy}`: Iri - filter the resulting realms based on the person which performed the last update. Optional parameter.
- `{sort}`: String - orders the resulting realms based on its metadata fields.  Optional parameter that can appear multiple times, further specifying the ordering criteria. Defaults to `_createdAt`, ordering realms by creation date.

Request
 :   @@snip [realms-list.sh](assets/realms/realms-list.sh)

Response
 :   @@snip [realms-list.json](assets/realms/realms-list.json)

## Fetch (current version)

```
GET /v1/realms/{realm}
```

**Example**

Request
:   @@snip [realm-fetch.sh](assets/realms/realm-fetch.sh)

Response
:   @@snip [realm-fetch.json](assets/realms/realm-fetch.json)


## Fetch (specific version)

```
GET /v1/realms/{realm}?rev={rev}
```
... where `{rev}` is the revision number of the resolver to be retrieved.

**Example**

Request
:   @@snip [realm-fetch-revision.sh](assets/realms/realm-fetch-revision.sh)

Response
:   @@snip [realm-fetch.json](assets/realms/realm-fetch.json)