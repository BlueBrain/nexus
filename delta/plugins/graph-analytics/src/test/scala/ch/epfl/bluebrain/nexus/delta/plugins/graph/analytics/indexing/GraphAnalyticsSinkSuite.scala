package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsResult.Index
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdDocument
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.testkit.bio.BioRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.bio.PatienceConfig
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import fs2.Chunk
import io.circe.Json
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class GraphAnalyticsSinkSuite
    extends CatsEffectSuite
    with BioRunContext
    with ElasticSearchClientSetup.Fixture
    with CirceLiteral
    with TestHelpers {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 50.millis)

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient)

  private lazy val client = esClient()

  private val index = IndexLabel.unsafe("test_analytics")

  private lazy val sink = new GraphAnalyticsSink(client, 5, 100.millis, index)

  private val project = ProjectRef.unsafe("myorg", "myproject")

  private val remoteContexts: Set[RemoteContextRef] =
    Set(RemoteContextRef.StaticContextRef(iri"https://bluebrain.github.io/nexus/contexts/metadata.json"))

  // resource1 has references to 'resource3', 'file1' and 'generatedBy',
  // 'generatedBy' remains unresolved
  private val resource1 = iri"http://localhost/resource1"
  private val expanded1 = loadExpanded("expanded/resource1.json")

  // resource2 has references to other resources
  // All of them should remain unresolved
  private val resource2 = iri"http://localhost/resource2"
  private val expanded2 = loadExpanded("expanded/resource2.json")

  // Deprecated resource
  private val deprecatedResource      = iri"http://localhost/deprecated"
  private val deprecatedResourceTypes =
    Set(iri"http://schema.org/Dataset", iri"https://neuroshapes.org/NeuroMorphology")

  // Resource linked by 'resource1', resolved while indexing
  private val resource3 = iri"http://localhost/resource3"
  // File linked by 'resource1', resolved after an update by query
  private val file1     = iri"http://localhost/file1"

  private def loadExpanded(path: String): IO[ExpandedJsonLd] =
    bioJsonContentOf(path)
      .flatMap { json =>
        Task.fromEither(ExpandedJsonLd.expanded(json))
      }
      .memoizeOnSuccess
      .toCatsIO

  private def getTypes(expandedJsonLd: ExpandedJsonLd): IO[Set[Iri]] =
    IO.pure(expandedJsonLd.cursor.getTypes.getOrElse(Set.empty))

  private val findRelationships: IO[Map[Iri, Set[Iri]]] = {
    for {
      resource1Types <- expanded1.flatMap(getTypes)
      resource2Types <- expanded2.flatMap(getTypes)
    } yield Map(
      resource1 -> resource1Types,
      resource2 -> resource2Types,
      resource3 -> Set(iri"https://neuroshapes.org/Trace")
    )
  }

  test("Create the update script and the index") {
    for {
      script  <- scriptContent
      _       <- client.createScript(updateRelationshipsScriptId, script)
      mapping <- graphAnalyticsMappings
      _       <- client.createIndex(index, Some(mapping), None).assertEquals(true)
    } yield ()
  }

  private def success(id: Iri, result: GraphAnalyticsResult) =
    SuccessElem(Resources.entityType, id, Some(project), Instant.EPOCH, Offset.start, result, 1)

  test("Push index results") {
    def indexActive(id: Iri, io: IO[ExpandedJsonLd]) = {
      for {
        expanded <- io
        types    <- getTypes(expanded)
        doc      <- JsonLdDocument.fromExpanded(expanded, _ => findRelationships)
      } yield {
        val result =
          Index.active(project, id, remoteContexts, 1, types, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, doc)
        success(id, result)
      }
    }

    def indexDeprecated(id: Iri, types: Set[Iri]) =
      success(
        id,
        Index.deprecated(project, id, remoteContexts, 1, types, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
      )

    for {
      active1            <- indexActive(resource1, expanded1)
      active2            <- indexActive(resource2, expanded2)
      discarded           = success(resource3, GraphAnalyticsResult.Noop)
      deprecated          = indexDeprecated(deprecatedResource, deprecatedResourceTypes)
      chunk               = Chunk.seq(List(active1, active2, discarded, deprecated))
      // We expect no error
      _                  <- sink(chunk).assertEquals(chunk.map(_.void))
      // 3 documents should have been indexed correctly:
      // - `resource1` with the relationship to `resource3` resolved
      // - `resource2` with no reference resolved
      // - `deprecatedResource` with only metadata, resolution is skipped
      _                  <- client.count(index.value).eventually(3L)
      expected1          <- ioJsonContentOf("result/resource1.json")
      expected2          <- ioJsonContentOf("result/resource2.json")
      expectedDeprecated <- ioJsonContentOf("result/resource_deprecated.json")
      _                  <- client.getSource[Json](index, resource1.toString).eventually(expected1)
      _                  <- client.getSource[Json](index, resource2.toString).eventually(expected2)
      _                  <- client.getSource[Json](index, deprecatedResource.toString).eventually(expectedDeprecated)
    } yield ()

  }

  test("Push update by query result results") {
    val chunk = Chunk.seq(
      List(
        success(file1, GraphAnalyticsResult.UpdateByQuery(file1, Set(nxvFile))),
        success(resource3, GraphAnalyticsResult.Noop),
        FailedElem(
          Resources.entityType,
          resource3,
          Some(project),
          Instant.EPOCH,
          Offset.start,
          new IllegalStateException("BOOM"),
          1
        )
      )
    )

    for {
      _         <- sink(chunk).assertEquals(chunk.map(_.void))
      // The reference to file1 should have been resolved and introduced as a relationship
      // The update query should not have an effect on the other resource
      _         <- client.refresh(index)
      expected1 <- ioJsonContentOf("result/resource1_updated.json")
      expected2 <- ioJsonContentOf("result/resource2.json")
      _         <- client.count(index.value).eventually(3L)
      _         <- client.getSource[Json](index, resource1.toString).eventually(expected1)
      _         <- client.getSource[Json](index, resource2.toString).eventually(expected2)
    } yield ()
  }

}
