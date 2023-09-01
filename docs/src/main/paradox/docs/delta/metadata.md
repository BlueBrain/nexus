# Nexus Metadata

Nexus Delta entities fall in two categories:

- Global: @ref:[Organizations](api/orgs-api.md), @ref:[Realms](api/realms-api.md),
  @ref:[Permissions](api/permissions-api.md), @ref[ACLs](api/acls-api.md)
- Project scoped: @ref:[Projects](api/projects-api.md), @ref:[Resources](api/resources-api.md),
  @ref:[Schemas](api/schemas-api.md), @ref:[Resolvers](api/resolvers-api.md),
  @ref:[Views](api/views/index.md), @ref:[Storages](api/storages-api.md), @ref:[Files](api/files-api.md)

Upon creation of these entities, Nexus Delta will create metadata fields which are described below.

- `_self`: unique address of the entity across Nexus Delta
    - Nexus Delta follows the @link:[HATEOAS](https://en.wikipedia.org/wiki/HATEOAS) architecture, which is reflected in
      the `_self` address being discoverable in Nexus Delta's different responses. Example: from a
      @ref[resources listing operation](api/resources-api.md#list), the `_self` endpoints listed can be used to
      @ref:[fetch](api/resources-api.md#fetch) the underlying resources

## Auditing

The following metadata can help to audit an entity.

- `_rev`: the revision number of the entity
- `_deprecated`: boolean indicating whether or not the entity is deprecated
- `_createdAt`: datetime at which the entity was first created
- `_createdBy`: the user that first created the entity
- `_updatedAt`: datetime at which the entity was last updated
- `_updatedBy`: the user that last updated the entity