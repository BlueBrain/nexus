# SDKs for Nexus

## Python SDK for Nexus
A Python wrapper for the Blue Brain Nexus REST API.

### How to install

'pip install nexus-sdk'

### Usage
```
import nexussdk as nexus

nexus.config.set_environment(DEPLOYMENT)
nexus.config.set_token(TOKEN)

nexus.permissions.fetch()
```

### List of supported operations

You can find the list of all supported operations at this [location](https://bluebrain.github.io/nexus-python-sdk/)

## JS SDK for Nexus

The [Javascript SDK](https://github.com/BlueBrain/nexus-sdk-js) provides many features to help you build web applications that integrate with Blue Brain Nexus.

![Nexus JS logo](./sdk/img/nexus-js-logo.png)

### How to install

`npm install @bbp/nexus-sdk`

### List of supported operations

You can find the list of all supported operations directly from the `nexus-sdk` [repository](https://github.com/BlueBrain/nexus-sdk-js#documentation)

### Typescript declarations

The SDK is written in Typescript, so type declarations for all operations are included in the package.

You can generated documentation using `npm run documentation` or with `docker` by running `make documentation`. More information can be found [here](https://github.com/BlueBrain/nexus-sdk-js#development)
