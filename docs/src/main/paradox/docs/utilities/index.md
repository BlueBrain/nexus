@@@ index

- @ref:[Indexing data in other systems](projections.md)
- @ref:[MovieLens Tutorial using the Nexus Python CLI](nexus-python-cli.md)

@@@

# Utilities

## Domains (Schemas, Vocabularies)

Nexus provides some schemas which make building provenance based knowledge graphs easier.

### Nexus Core Schemas

#### nexus-schemaorg

@link:[SHACL](https://www.w3.org/TR/shacl/){ open=new } version of a subset of schemas defined by
@link:[schema.org](https://schema.org/docs/full.html){ open=new } that are commonly used in Blue Brain Nexus.

@link:[Source Code](https://github.com/BlueBrain/nexus-schemaorg){ open=new }

#### nexus-prov

Data management oriented @link:[SHACL](https://www.w3.org/TR/shacl/){ open=new } version of
@link:[W3C PROV-O](https://www.w3.org/ns/prov-o-20130430){ open=new }.

@link:[Source Code](https://github.com/BlueBrain/nexus-prov){ open=new }

### Nexus domain specific components

#### Nexus BBP Data models

Data models that Blue Brain has developed in order to facilitate the integration of specific neuroscience data.

@link:[Source Code](https://github.com/BlueBrain/nexus-bbp-domains){ open=new }

#### Neuroshapes

A community effort to develop open SHACL schemas for FAIR (Findable, Accessible, Interoperable, Reproducible)
neuroscience data.

@link:[Source Code](https://github.com/INCF/neuroshapes){ open=new }

## Nexus Tools

### Nexus CLI

A command line interface (CLI) to perform basic operations on a Nexus deployment.

@link:[Source Code](https://github.com/BlueBrain/nexus-cli){ open=new }

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

@link:[Source Code](https://github.com/BlueBrain/nexus-python-sdk){ open=new } | @link:[Documentation](https://bluebrain.github.io/nexus-python-sdk/){ open=new }

### Nexus.js

The @link:[Javascript SDK](https://github.com/BlueBrain/nexus-js){ open=new } provides many features to help you 
build web applications that integrate with Blue Brain Nexus.

![Nexus JS logo](../assets/nexus-js-logo.png)

#### How to install Nexus.js

`npm install @bbp/nexus-sdk`

#### Typescript declarations

The SDK is written in Typescript, so type declarations for all operations are included in the package.

You can generate documentation using `npm run documentation` or with `docker` by running `make documentation`. 
More information can be found @link:[here](https://github.com/BlueBrain/nexus-js){ open=new }.

@link:[Source Code](https://github.com/BlueBrain/nexus-js){ open=new }

Documentation:

- @link:[nexus-sdk](https://github.com/BlueBrain/nexus-js/blob/main/packages/nexus-sdk/README.md#readme){ open=new }

### Other JavaScript Packages

#### React-Nexus

This package contains some utility components to easily integrate the Nexus SDK as React hooks or contexts.

- @link:[react-nexus](https://github.com/BlueBrain/nexus-js/blob/main/packages/react-nexus/README.md#readme){ open=new }

#### Nexus-Link

Another utility packages was written to facilitate chained calling behavior, which can be used independently of Nexus.js for other projects.

- @link:[nexus-link](https://github.com/BlueBrain/nexus-js/blob/main/packages/nexus-link/README.md#readme){ open=new }

### Indexing data in other systems

Nexus also provides a CLI tool to index data into other systems. PostgreSQL and InfluxDB are currently supported.
More information about this tool can be found @ref:[here](projections.md).
