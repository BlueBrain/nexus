# Authentication

In order to interact with Nexus Delta, clients need a valid access token. Tokens can be obtained from authentication providers.
Please see, @ref:[realms](iam-realms-api.md) for documentation on how to find available providers(realms).

Each realm defines
`openid-configuration` endpoint. From that endpoint, clients can obtain the information necessary to 
obtain an access token, especially the `authorize` and `token` endpoints for the provider.

Please see @link:[oauth2 documentation](https://www.oauth.com/){ open=new }
for different authentication flows available for different types of applications. 