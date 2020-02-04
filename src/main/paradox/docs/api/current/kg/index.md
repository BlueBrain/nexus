@@@ index

* [Schemas](kg-schemas-api.md)
* [Resolvers](kg-resolvers-api.md)
* [Views](views/index.md)
* [Storages](kg-storages-api.md)
* [Files](kg-files-api.md)
* [Archives](kg-archives-api.md)
* [Resources](kg-resources-api.md)

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

@ref:[Operations on views](views/index.md)

## Storages

A storage is a resource which represents a backend where files are stored. It describes where and how files are created and retrieve.

@ref:[Operations on storages](kg-storages-api.md)

## Files

A file is a binary attachment resource.

@ref:[Operations on files](kg-files-api.md)

## Archives

An archive is a collection of resources stored inside an archive file. The archiving format chosen for this purpose is tar (or tarball).

@ref:[Operations on archives](kg-archives-api.md)

## Resources

A resource is the most generic entity on the Knowledge Graph. Resources can be `schemas`, `resolvers`, `views`,
`storages`, `files` or `data`.

@ref:[Operations on resources](kg-resources-api.md)
