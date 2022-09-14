package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.{elasticsearch, elasticsearchMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, logStatesDef, noopPipeDef, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources.{ScopedStateSource, StreamSource}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.IO

import java.util.UUID

trait Fixtures extends IOValues {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  def alwaysValidate: ValidateElasticSearchView = (_: UUID, _: Int, _: ElasticSearchViewValue) => IO.unit

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

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

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
    Vocabulary.contexts.pipeline   -> ContextValue.fromFile("contexts/pipeline.json").accepted,
    Vocabulary.contexts.tags       -> ContextValue.fromFile("contexts/tags.json").accepted,
    Vocabulary.contexts.search     -> ContextValue.fromFile("contexts/search.json").accepted
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
    r.register(StreamSource[UniformScopedState](ScopedStateSource.label, _ => fs2.Stream.empty))
    r.register(noopPipeDef)
    r.register(logStatesDef)
    r
  }
}

object Fixtures extends Fixtures
