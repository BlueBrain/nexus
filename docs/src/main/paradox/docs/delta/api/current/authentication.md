# Authentication & authorization

**Authentication** is the process of validating that users are who they claim to be while **authorization** gives those users permission to access an API resource.

## Authentication

In order to interact with Nexus Delta, clients need a valid access token in order to claim their identity. Tokens can be obtained from authentication providers.
Please see, @ref:[realms](iam-realms-api.md) for documentation on how to find available providers(realms).

Each realm defines `openid-configuration` endpoint. From that endpoint, clients can obtain the information necessary to 
acquire an access token, especially the `authorize` and `token` endpoints for the provider.

Please see @link:[oauth2 documentation](https://www.oauth.com/){ open=new } for different authentication flows available for different types of applications. 

## Authorization

The access token obtained during the authentication process has a series of @ref:[identities](iam-identities.md) 
that the authorization process leverages to grant access to certain API resources.

The configuration of Nexus Delta access control for different identities is described in the @ref:[ACLs section](iam-acls-api.md) of the documentation. Each API resource section describes the required @ref:[permissions](iam-permissions-api.md) an identity must have in order to be able to access that endpoint.

A client should provide the access token information to Nexus Delta when accessing a protected API endpoint through the
Authorization HTTP header.

**Example**

```bash
curl -H "Authorization: Bearer {ACCESS_TOKEN}" "http://localhost:8080/v1/version"
```