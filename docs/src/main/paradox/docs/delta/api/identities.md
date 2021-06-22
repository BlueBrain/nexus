# Identities

The `/v1/identities` endpoint allows user to retrieve the identities that the user has in the platform. Calling the 
endpoint without a token will result in only one identity returned: `Anonymous`.
Calling it with token should return multiple identities. There are four different types of identities:

- `Anonymous` - represents anonymous user
- `Authenticated` - represents a realm via which the user is authenticated
- `Group` - represents a group to which a user belongs in a realm
- `User` - represents the user.

The following HTTP call can used to retrieve the identities:
```
GET /v1/identities
```

**Example**

Request
:   @@snip [identities-fetch.sh](assets/identities/identities-fetch.sh)

Response
:   @@snip [identities-fetch-response.json](assets/identities/identities-fetch-response.json)
