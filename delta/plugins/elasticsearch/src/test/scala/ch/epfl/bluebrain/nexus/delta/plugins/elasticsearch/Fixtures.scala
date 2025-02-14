package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.{elasticsearch, elasticsearchMetadata}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._

object Fixtures {
  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader()
}

trait Fixtures {

  import Fixtures._

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
    ).foldLeftM(ContextValue.empty) { case (acc, file) =>
      ContextValue.fromFile(file).map(acc.merge)
    }

  private val indexingMetadataCtx = listingsMetadataCtx.map(_.visit(obj = { case ContextObject(obj) =>
    ContextObject(obj.filterKeys(_.startsWith("_")))
  }))

  implicit val api: JsonLdApi = TitaniumJsonLdApi.strict

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixedIOResource(
    elasticsearch                  -> ContextValue.fromFile("contexts/elasticsearch.json"),
    elasticsearchMetadata          -> ContextValue.fromFile("contexts/elasticsearch-metadata.json"),
    contexts.aggregations          -> ContextValue.fromFile("contexts/aggregations.json"),
    contexts.elasticsearchIndexing -> ContextValue.fromFile("contexts/elasticsearch-indexing.json"),
    contexts.searchMetadata        -> listingsMetadataCtx,
    contexts.indexingMetadata      -> indexingMetadataCtx,
    Vocabulary.contexts.metadata   -> ContextValue.fromFile("contexts/metadata.json"),
    Vocabulary.contexts.error      -> ContextValue.fromFile("contexts/error.json"),
    Vocabulary.contexts.metadata   -> ContextValue.fromFile("contexts/metadata.json"),
    Vocabulary.contexts.error      -> ContextValue.fromFile("contexts/error.json"),
    Vocabulary.contexts.shacl      -> ContextValue.fromFile("contexts/shacl.json"),
    Vocabulary.contexts.statistics -> ContextValue.fromFile("contexts/statistics.json"),
    Vocabulary.contexts.offset     -> ContextValue.fromFile("contexts/offset.json"),
    Vocabulary.contexts.pipeline   -> ContextValue.fromFile("contexts/pipeline.json"),
    Vocabulary.contexts.tags       -> ContextValue.fromFile("contexts/tags.json"),
    Vocabulary.contexts.search     -> ContextValue.fromFile("contexts/search.json")
  )

  val registry: ReferenceRegistry = {
    val r = new ReferenceRegistry
    r.register(SourceAsText)
    r.register(FilterDeprecated)
    r.register(DefaultLabelPredicates)
    r.register(DiscardMetadata)
    r.register(FilterBySchema)
    r.register(FilterByType)
    r.register(DataConstructQuery)
    r.register(SelectPredicates)
    r
  }
}
