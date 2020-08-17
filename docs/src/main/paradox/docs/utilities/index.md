@@@ index

* [Indexing data in other systems](projections.md)
* [MovieLens Tutorial using the Nexus Python CLI](nexus-python-cli.md)

@@@

# Utilities

## Domains (Schemas, Vocabularies)

Nexus provides some schemas which make building provenance based knowledge graphs easier.

### Nexus Core Schemas

#### nexus-schemaorg

[SHACL](https://www.w3.org/TR/shacl/) version of a subset of schemas defined by
[schema.org](http://schema.org/docs/full.html) that are commonly used in Blue Brain Nexus.

[Source Code](https://github.com/BlueBrain/nexus-schemaorg)

#### nexus-prov

Data management oriented [SHACL](https://www.w3.org/TR/shacl/) version of
[W3C PROV-O](http://www.w3.org/ns/prov-o-20130430).

[Source Code](https://github.com/BlueBrain/nexus-prov)

### Nexus domain specific components

#### Nexus BBP Data models

Data models that Blue Brain has developed in order to facilitate the integration of specific neuroscience data.

[Source Code](https://github.com/BlueBrain/nexus-bbp-domains)

#### Neuroshapes

A community effort to develop open SHACL schemas for FAIR (Findable, Accessible, Interoperable, Reproducible)
neuroscience data.

[Source Code](https://github.com/INCF/neuroshapes)

## Nexus Tools

### Nexus CLI

A command line interface (CLI) to perform basic operations on a Nexus deployment.

[Source Code](https://github.com/BlueBrain/nexus-cli)

### Nexus Python SDK

A Python wrapper for the Blue Brain Nexus REST API.

#### How to install the Nexus Python SDK

`pip install nexus-sdk`

#### Usage

```
import nexussdk as nexus

nexus.config.set_environment(DEPLOYMENT)
nexus.config.set_token(TOKEN)

nexus.permissions.fetch()
```

[Source Code](https://github.com/BlueBrain/nexus-python-sdk) | [Documentation](https://bluebrain.github.io/nexus-python-sdk/)

### Nexus.js

The [Javascript SDK](https://github.com/BlueBrain/nexus-sdk-js) provides many features to help you build web applications that integrate with Blue Brain Nexus.

![Nexus JS logo](../assets/nexus-js-logo.png)

#### How to install Nexus.js

`npm install @bbp/nexus-sdk`

#### Typescript declarations

The SDK is written in Typescript, so type declarations for all operations are included in the package.

You can generate documentation using `npm run documentation` or with `docker` by running `make documentation`. More information can be found [here](https://github.com/BlueBrain/nexus-sdk-js#development).

[Source Code](https://github.com/BlueBrain/nexus-sdk-js)

Documentation:

- [nexus-sdk](https://github.com/BlueBrain/nexus-js/blob/master/packages/nexus-sdk/README.md#readme)

### Other JavaScript Packages

#### React-Nexus

This package contains some utility components to easily integrate the Nexus SDK as React hooks or contexts.

- [react-nexus](https://github.com/BlueBrain/nexus-js/blob/master/packages/react-nexus/README.md#readme)

#### Nexus-Link

Another utility packages was written to facilitate chained calling behavior, which can be used independently of Nexus.js for other projects.

- [nexus-link](https://github.com/BlueBrain/nexus-js/blob/master/packages/nexus-link/README.md#readme)

### Indexing data in other systems

Nexus also provides a CLI tool to index data into other systems. PostgreSQL and InfluxDB are currently supported.
More information about this tool can be found @ref:[here](projections.md).
