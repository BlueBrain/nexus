@@@ index

* [Schemas](kg-schemas-api.md)
* [Resolvers](kg-resolvers-api.md)
* [Views](kg-views-api.md)
* [Resources](kg-resources-api.md)
* [Resources](kg-data-api.md)


@@@

# Knowledge Graph API

## Schemas
A schema is a resource which defines a set of rules and constrains using [SHACL](https://www.w3.org/TR/shacl/). 

@ref:[Operations on schemas](kg-schemas-api.md)

## Resolvers
A resolver is a resource which defines the way ids are retrieved inside a project.

@ref:[Operations on resolvers](kg-resolvers-api.md)

## Views
A view is a resource which defines the way indexing is applied to certain resources inside a project.

@ref:[Operations on views](kg-views-api.md)

## Resources
A resource is the most generic entity on the Knowledge Graph. Resources can be `schemas`, `resolvers`, `views` or `data`.

@ref:[Operations on resources](kg-resources-api.md)


## Data
Data endpoints are simply a shortcut to access some of the @ref:[operations on resources](kg-resources-api.md). In these endpoints the `{schema}` segment is omitted.

@ref:[Operations on data](kg-data-api.md)
