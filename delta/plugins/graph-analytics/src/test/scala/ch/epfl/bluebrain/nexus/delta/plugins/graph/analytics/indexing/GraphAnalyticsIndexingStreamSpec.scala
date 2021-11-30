package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.persistence.query.{Offset, Sequence}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.ResourceParser
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingStream.EventStream
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingStreamSpec.OtherEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.{FileCreated, FileUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileAttributes, FileEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ResourceGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.UnScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{Resource, ResourceEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues, TestHelpers}
import fs2.Stream
import io.circe.{Json, JsonObject}
import monix.bio.UIO
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

@DoNotDiscover
class GraphAnalyticsIndexingStreamSpec
    extends TestKit(ActorSystem("GraphAnalyticsIndexingStreamSpec"))
    with AnyWordSpecLike
    with EitherValuable
    with ElasticSearchClientSetup
    with Matchers
    with TestHelpers
    with IOValues
    with IOFixedClock
    with ConfigFixtures
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(20.seconds, Span(10, Millis))

  "GraphAnalyticsIndexingStream" should {
    val project = ProjectRef.unsafe("myorg", "myproject")

    val resource1 = iri"http://localhost/resource1"
    val resource2 = iri"http://localhost/resource2"
    val resource3 = iri"http://localhost/resource3"
    val file1     = iri"http://localhost/file1"

    val stream: EventStream =
      Stream
        .iterable(
          List(
            resourceEvent(resource1, project, 1L),
            resourceEvent(resource1, project, 2L),
            otherEvent,
            resourceEvent(resource2, project, 1L),
            fileEvent(file1, project, 1L),
            otherEvent,
            resourceEvent(resource1, project, 4L),
            resourceEvent(resource2, project, 3L)
          )
        )
        .zipWithIndex
        .map { case (event, index) =>
          Envelope(event, Sequence(index), s"event-$index", index)
        }

    val fetchResource: (Iri, ProjectRef) => UIO[Option[DataResource]] = {
      case (`resource1`, `project`) =>
        UIO.some(
          resourceF(resource1, project, 4L, jsonContentOf("expanded/resource1.json"))
        )
      case (`resource2`, `project`) =>
        UIO.some(
          resourceF(resource2, project, 4L, jsonContentOf("expanded/resource2.json"))
        )
      case _                        => UIO.none
    }

    val findRelationship: (Iri, ProjectRef) => UIO[Option[Set[Iri]]] = {
      case (`resource1`, `project`) =>
        fetchResource(resource1, project).map(_.map(_.types))
      case (`resource2`, `project`) =>
        fetchResource(resource2, project).map(_.map(_.types))
      case (`resource3`, `project`) =>
        UIO.some(Set(iri"https://neuroshapes.org/Trace"))
      case (`file1`, `project`)     =>
        UIO.some(Set(nxvFile))
      case _                        => UIO.none
    }

    val resourceParser = ResourceParser(fetchResource, findRelationship)

    val projection: Projection[Unit] = Projection.inMemory(()).accepted
    val progressesCache              = KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]](10L).accepted

    val mapping     = jsonObjectContentOf("elasticsearch/mappings.json")
    val graphStream = new GraphAnalyticsIndexingStream(
      esClient,
      (_: ProjectRef, _: Offset) => UIO.pure(stream),
      resourceParser,
      progressesCache,
      externalIndexing,
      projection
    )

    val projectionId = ViewProjectionId("analytics")

    "generate graph analytics index with statistics data" in {
      val viewIndex = ViewIndex(
        project,
        iri"",
        UUID.randomUUID(),
        projectionId,
        "idx",
        1,
        false,
        None,
        Instant.EPOCH,
        GraphAnalyticsView(mapping)
      )
      graphStream(viewIndex, ProgressStrategy.FullRestart).accepted.compile.toList.accepted
      eventually {
        esClient
          .search(JsonObject.empty, Set("idx"), Uri.Query.Empty)()
          .accepted
          .removeKeys("took", "_shards") shouldEqual
          jsonContentOf("indexed-documents.json")
      }
    }

    "obtain the adequate progress for the projection" in eventually {
      projection.progress(projectionId).accepted shouldEqual
        ProjectionProgress(Sequence(7L), Instant.EPOCH, 8L, 5L, 0L, 0L)
    }
  }

  def resourceEvent(id: Iri, project: ProjectRef, rev: Long): ResourceEvent =
    if (rev > 1L)
      ResourceUpdated(
        id,
        project,
        Revision(schemas.resources, 1L),
        project,
        Set.empty,
        Json.obj(),
        CompactedJsonLd.empty,
        ExpandedJsonLd.empty,
        rev,
        Instant.EPOCH,
        Anonymous
      )
    else
      ResourceCreated(
        id,
        project,
        Revision(schemas.resources, 1L),
        project,
        Set.empty,
        Json.obj(),
        CompactedJsonLd.empty,
        ExpandedJsonLd.empty,
        rev,
        Instant.EPOCH,
        Anonymous
      )

  def fileEvent(id: Iri, project: ProjectRef, rev: Long): FileEvent = {
    val fileAttributes = FileAttributes(
      UUID.randomUUID(),
      "http://localhost/file.txt",
      Uri.Path("file.txt"),
      "file.txt",
      Some(`text/plain(UTF-8)`),
      12,
      Digest.NotComputedDigest,
      Client
    )
    if (rev > 1L)
      FileUpdated(
        id,
        project,
        Revision(iri"http://localhost/my-storage", 1L),
        StorageType.DiskStorage,
        fileAttributes,
        rev,
        Instant.EPOCH,
        Anonymous
      )
    else
      FileCreated(
        id,
        project,
        Revision(iri"http://localhost/my-storage", 1L),
        StorageType.DiskStorage,
        fileAttributes,
        rev,
        Instant.EPOCH,
        Anonymous
      )
  }

  def otherEvent: OtherEvent = OtherEvent(1L, Instant.EPOCH, Anonymous)

  def resourceF(id: Iri, project: ProjectRef, rev: Long, json: Json): DataResource = {
    val expanded = ExpandedJsonLd.expanded(json).rightValue
    ResourceGen.resourceFor(
      Resource(
        id,
        project,
        Map.empty,
        Latest(schemas.resources),
        Json.obj(),
        CompactedJsonLd.empty,
        expanded
      ),
      expanded.cursor.getTypes.rightValue,
      rev = rev
    )
  }
}

object GraphAnalyticsIndexingStreamSpec {
  final case class OtherEvent(rev: Long, instant: Instant, subject: Subject) extends UnScopedEvent
}
