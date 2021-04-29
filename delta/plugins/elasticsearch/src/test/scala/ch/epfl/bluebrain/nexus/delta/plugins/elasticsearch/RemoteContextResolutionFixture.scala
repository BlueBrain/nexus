package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.{elasticsearch, elasticsearchMetadata}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.testkit.IOValues

trait RemoteContextResolutionFixture extends IOValues {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  private val listingsMetadataCtx =
    List(
      "contexts/acls-metadata.json",
      "contexts/realms-metadata.json",
      "contexts/organizations-metadata.json",
      "contexts/projects-metadata.json",
      "contexts/resolvers-metadata.json",
      "contexts/schemas-metadata.json",
      "contexts/elasticsearch-metadata.json",
      "contexts/metadata.json"
    ).foldLeft(ContextValue.empty)(_ merge ContextValue.fromFile(_).accepted)

  private val indexingMetadataCtx = listingsMetadataCtx.visit(obj = { case ContextObject(obj) =>
    ContextObject(obj.filterKeys(_.startsWith("_")))
  })

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    elasticsearch                  -> ContextValue.fromFile("contexts/elasticsearch.json").accepted,
    elasticsearchMetadata          -> ContextValue.fromFile("contexts/elasticsearch-metadata.json").accepted,
    contexts.elasticsearchIndexing -> ContextValue.fromFile("/contexts/elasticsearch-indexing.json").accepted,
    contexts.searchMetadata        -> listingsMetadataCtx,
    contexts.indexingMetadata      -> indexingMetadataCtx,
    Vocabulary.contexts.metadata   -> ContextValue.fromFile("contexts/metadata.json").accepted,
    Vocabulary.contexts.error      -> ContextValue.fromFile("contexts/error.json").accepted,
    Vocabulary.contexts.metadata   -> ContextValue.fromFile("contexts/metadata.json").accepted,
    Vocabulary.contexts.error      -> ContextValue.fromFile("contexts/error.json").accepted,
    Vocabulary.contexts.shacl      -> ContextValue.fromFile("contexts/shacl.json").accepted,
    Vocabulary.contexts.statistics -> ContextValue.fromFile("/contexts/statistics.json").accepted,
    Vocabulary.contexts.offset     -> ContextValue.fromFile("/contexts/offset.json").accepted,
    Vocabulary.contexts.tags       -> ContextValue.fromFile("contexts/tags.json").accepted,
    Vocabulary.contexts.search     -> ContextValue.fromFile("contexts/search.json").accepted
  )
}
