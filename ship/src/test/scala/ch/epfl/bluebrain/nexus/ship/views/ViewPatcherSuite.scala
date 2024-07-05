package ch.epfl.bluebrain.nexus.ship.views

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.Database._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViewJsonLdSourceDecoder
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.Database._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts.{elasticsearch, elasticsearchMetadata}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.{PipeStep, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterByType
import ch.epfl.bluebrain.nexus.ship.{IriPatcher, ProjectMapper}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.syntax.EncoderOps

import java.util.UUID

class ViewPatcherSuite extends NexusSuite {

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixedIOResource(
    elasticsearch                  -> ContextValue.fromFile("contexts/elasticsearch.json"),
    elasticsearchMetadata          -> ContextValue.fromFile("contexts/elasticsearch-metadata.json"),
    contexts.aggregations          -> ContextValue.fromFile("contexts/aggregations.json"),
    contexts.elasticsearchIndexing -> ContextValue.fromFile("contexts/elasticsearch-indexing.json"),
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
  implicit val api: JsonLdApi               = JsonLdJavaApi.strict
  private val ref                           = ProjectRef.unsafe("org", "proj")
  private val context                       = ProjectContext.unsafe(
    ApiMappings("_" -> schemas.resources, "resource" -> schemas.resources),
    iri"http://localhost/v1/resources/org/proj/_/",
    iri"http://schema.org/",
    enforceSchema = false
  )

  implicit private val uuidF: UUIDF = UUIDF.fixed(UUID.randomUUID())

  implicit private val resolverContext: ResolverContextResolution = ResolverContextResolution(rcr)

  implicit private val caller: Caller = Caller.Anonymous
  private val decoder                 =
    ElasticSearchViewJsonLdSourceDecoder(uuidF, resolverContext).unsafeRunSync()

  private val project1 = ProjectRef.unsafe("org", "project")
  private val viewId1  = iri"https://bbp.epfl.ch/view1"
  private val view1    = ViewRef(project1, viewId1)

  private val project2 = ProjectRef.unsafe("org", "project2")
  private val viewId2  = iri"https://another-view-prefix/view2"
  private val view2    = ViewRef(project2, viewId2)

  private val aggregateView: ElasticSearchViewValue =
    AggregateElasticSearchViewValue(None, None, NonEmptySet.of(view1, view2))

  private val originalPrefix = iri"https://bbp.epfl.ch/"
  private val targetPrefix   = iri"https://openbrainplatform.com/"

  private val targetProject = ProjectRef.unsafe("new-org", "new-project2")

  private val iriPatcher    = IriPatcher(originalPrefix, targetPrefix, Map.empty)
  private val projectMapper = ProjectMapper(Map(project2 -> targetProject))
  private val viewPatcher   = new ViewPatcher(projectMapper, iriPatcher)

  private def indexingESViewWithFilterByTypePipeline(types: Iri*): IndexingElasticSearchViewValue = {
    IndexingElasticSearchViewValue(
      None,
      None,
      pipeline = filterByTypePipeline(types: _*)
    )
  }

  private def filterByTypePipeline(types: Iri*) = List(
    PipeStep(FilterByType(IriFilter.fromSet(types.toSet)))
  )

  test("Patch the aggregate view") {
    val viewAsJson         = aggregateView.asJson
    val expectedView1      = ViewRef(project1, iri"https://openbrainplatform.com/view1")
    val expectedView2      = ViewRef(targetProject, viewId2)
    val expectedAggregated = AggregateElasticSearchViewValue(None, None, NonEmptySet.of(expectedView1, expectedView2))
    val result             = viewPatcher.patchElasticSearchViewSource(viewAsJson).as[ElasticSearchViewValue]
    assertEquals(result, Right(expectedAggregated))
  }

  test("Patch an ES view's resource types") {
    val view         = indexingESViewWithFilterByTypePipeline(originalPrefix / "Type1", originalPrefix / "Type2")
    val viewAsJson   = view.asInstanceOf[ElasticSearchViewValue].asJson
    val expectedView = indexingESViewWithFilterByTypePipeline(targetPrefix / "Type1", targetPrefix / "Type2")
    val result       = viewPatcher.patchElasticSearchViewSource(viewAsJson).as[ElasticSearchViewValue]
    assertEquals(result, Right(expectedView))
  }

  test("Patch a legacy ES view's resource types") {
    val sourceJson = json"""{
      "@type": "ElasticSearchView",
      "resourceTypes": [ "${originalPrefix / "Type1"}", "${originalPrefix / "Type2"}" ],
      "mapping": { }
    }"""

    val (_, originalValue) = decoder(ref, context, sourceJson).accepted
    assertEquals(
      originalValue.asIndexingValue.map(_.pipeline.filter(_.name.value == "filterByType")),
      Some(filterByTypePipeline(originalPrefix / "Type1", originalPrefix / "Type2"))
    )

    val patchedJson       = viewPatcher.patchElasticSearchViewSource(sourceJson)
    val (_, patchedValue) = decoder(ref, context, patchedJson).accepted
    assertEquals(
      patchedValue.asIndexingValue.map(_.pipeline.filter(_.name.value == "filterByType")),
      Some(filterByTypePipeline(targetPrefix / "Type1", targetPrefix / "Type2"))
    )
  }

  test("Patch a blazegraph view's resource types") {
    val view: BlazegraphViewValue = IndexingBlazegraphViewValue(resourceTypes =
      IriFilter.fromSet(Set(iri"https://bbp.epfl.ch/resource1", iri"https://bbp.epfl.ch/resource2"))
    )

    val patchedView = viewPatcher.patchBlazegraphViewSource(view.asJson).as[BlazegraphViewValue]

    assertEquals(
      patchedView,
      Right(
        IndexingBlazegraphViewValue(resourceTypes =
          IriFilter.fromSet(
            Set(iri"https://openbrainplatform.com/resource1", iri"https://openbrainplatform.com/resource2")
          )
        )
      )
    )
  }

}
