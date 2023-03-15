package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdDocument
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import fs2.Chunk
import io.circe.JsonObject
import monix.bio.{Task, UIO}
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class GraphAnalyticsSinkSuite
    extends BioSuite
    with ElasticSearchClientSetup.Fixture
    with CirceLiteral
    with TestHelpers {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 50.millis)

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient)

  private lazy val client = esClient()

  private val index = IndexLabel.unsafe("test_analytics")

  private lazy val sink = new GraphAnalyticsSink(client, 2, 100.millis, index)

  private val project = ProjectRef.unsafe("myorg", "myproject")

  private val resource1 = iri"http://localhost/resource1"
  private val resource2 = iri"http://localhost/resource2"
  val resource3         = iri"http://localhost/resource3"
  val file1             = iri"http://localhost/file1"

  private def loadExpanded(path: String): UIO[ExpandedJsonLd] =
    ioJsonContentOf(path)
      .flatMap { json =>
        Task.fromEither(ExpandedJsonLd.expanded(json))
      }
      .memoizeOnSuccess
      .hideErrors

  private def getTypes(expandedJsonLd: ExpandedJsonLd): UIO[Option[Set[Iri]]] =
    UIO.pure(expandedJsonLd.cursor.getTypes.toOption)

  private val expanded1 = loadExpanded("expanded/resource1.json")
  private val expanded2 = loadExpanded("expanded/resource2.json")

  val findRelationship: Iri => UIO[Option[Set[Iri]]] = {
    case `resource1` =>
      expanded1.flatMap(getTypes)
    case `resource2` =>
      expanded2.flatMap(getTypes)
    case `resource3` =>
      UIO.some(Set(iri"https://neuroshapes.org/Trace"))
    case _           => UIO.none
  }

  test("Create the update script and the index") {
    for {
      script  <- scriptContent
      _       <- client.createScript(updateRelationshipsScriptId, script)
      mapping <- graphAnalyticsMappings
      _       <- client.createIndex(index, Some(mapping), None).assert(true)
    } yield ()
  }

  test("Push index results") {
    def toIndex(id: Iri, io: UIO[ExpandedJsonLd]) = {
      for {
        expanded <- io
        types    <- getTypes(expanded)
        doc      <- JsonLdDocument.fromExpanded(expanded, findRelationship)
      } yield {
        val result = GraphAnalyticsResult.Index(
          id,
          1,
          types.getOrElse(Set.empty),
          Instant.EPOCH,
          Anonymous,
          Instant.EPOCH,
          Anonymous,
          doc
        )
        SuccessElem(Resources.entityType, id, Some(project), Instant.EPOCH, Offset.start, result, 1)
      }
    }

    for {
      r1                <- toIndex(resource1, expanded1)
      r2                <- toIndex(resource2, expanded2)
      r3                 = SuccessElem(
                             Resources.entityType,
                             resource3,
                             Some(project),
                             Instant.EPOCH,
                             Offset.start,
                             GraphAnalyticsResult.Noop,
                             1
                           )
      chunk              = Chunk.seq(List(r1, r2, r3))
      // We expect no error
      _                 <- sink(chunk).assert(chunk.map(_.void))
      // Elasticsearch should have taken into account the bulk query and the update query
      expectedDocuments <- ioJsonContentOf("indexed-documents.json")
      sort               = SortList(Sort("@id") :: Nil)
      _                 <- client
                             .search(JsonObject.empty, Set(index.value), Uri.Query.Empty)(sort)
                             .map(_.removeKeys("took", "_shards"))
                             .eventually(expectedDocuments)
    } yield ()

  }

  test("Push update by query result results") {
    val chunk = Chunk.seq(
      List(
        SuccessElem(
          Resources.entityType,
          resource3,
          Some(project),
          Instant.EPOCH,
          Offset.start,
          GraphAnalyticsResult.UpdateByQuery(file1, Set(nxvFile)),
          1
        ),
        SuccessElem(
          Resources.entityType,
          resource3,
          Some(project),
          Instant.EPOCH,
          Offset.start,
          GraphAnalyticsResult.Noop,
          1
        ),
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
      _                 <- sink(chunk).assert(chunk.map(_.void))
      expectedDocuments <- ioJsonContentOf("indexed-documents-after-query.json")
      sort               = SortList(Sort("@id") :: Nil)
      _                 <- client
                             .search(JsonObject.empty, Set(index.value), Uri.Query.Empty)(sort)
                             .map(_.removeKeys("took", "_shards"))
                             .eventually(expectedDocuments)
    } yield ()
  }

}
