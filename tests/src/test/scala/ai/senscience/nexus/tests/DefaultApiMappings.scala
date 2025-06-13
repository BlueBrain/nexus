package ai.senscience.nexus.tests

object DefaultApiMappings {

  val value = Map(
    "defaultResolver" -> "https://bluebrain.github.io/nexus/vocabulary/defaultInProject",
    "resolver"        -> "https://bluebrain.github.io/nexus/schemas/resolvers.json",
    "schema"          -> "https://bluebrain.github.io/nexus/schemas/shacl-20170720.ttl",
    "_"               -> "https://bluebrain.github.io/nexus/schemas/unconstrained.json",
    "resource"        -> "https://bluebrain.github.io/nexus/schemas/unconstrained.json",
    "view"            -> "https://bluebrain.github.io/nexus/schemas/views.json",
    "graph"           -> "https://bluebrain.github.io/nexus/vocabulary/defaultSparqlIndex",
    "storage"         -> "https://bluebrain.github.io/nexus/schemas/storages.json",
    "defaultStorage"  -> "https://bluebrain.github.io/nexus/vocabulary/diskStorageDefault",
    "file"            -> "https://bluebrain.github.io/nexus/schemas/files.json",
    "archive"         -> "https://bluebrain.github.io/nexus/schemas/archives.json",
    "search"          -> "https://bluebrain.github.io/nexus/vocabulary/searchView"
  )

}
