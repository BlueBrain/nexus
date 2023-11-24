package ch.epfl.bluebrain.nexus.delta.sourcing.query

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuerySuite.Release
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStore
import ch.epfl.bluebrain.nexus.delta.sourcing.{PullRequest, Serializer}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import fs2.Chunk
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, DecodingFailure, Json}
import munit.AnyFixture

import java.time.Instant
import scala.annotation.nowarn

class StreamingQuerySuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private val qc = QueryConfig(2, RefreshStrategy.Stop)

  private lazy val xas = doobie()

  private lazy val prStore = ScopedStateStore[Iri, PullRequestState](
    PullRequest.entityType,
    PullRequestState.serializer,
    qc,
    xas
  )

  private lazy val releaseStore = ScopedStateStore[Iri, Release](
    Release.entityType,
    Release.serializer,
    qc,
    xas
  )

  private val alice     = User("Alice", Label.unsafe("Wonderland"))
  private val project1  = ProjectRef.unsafe("org", "proj1")
  private val project2  = ProjectRef.unsafe("org", "proj2")
  private val project3  = ProjectRef.unsafe("org2", "proj2")
  private val id1       = nxv + "1"
  private val id2       = nxv + "2"
  private val id3       = nxv + "3"
  private val id4       = nxv + "4"
  private val customTag = UserTag.unsafe("v0.1")
  private val rev       = 1

  private val prState11 = PullRequestActive(id1, project1, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val prState12 = PullRequestActive(id2, project1, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val prState13 =
    PullRequestActive(id3, project1, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice, Set(nxv + "Fix"))
  private val prState14 =
    PullRequestActive(id4, project1, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice, Set(nxv + "Feature"))
  private val prState21 = PullRequestActive(id1, project2, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val prState34 = PullRequestActive(id4, project3, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private val release11 = Release(nxv + "a", project1, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val release12 = Release(nxv + "b", project1, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val release21 = Release(nxv + "c", project2, rev, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private def decodeValue(entityType: EntityType, json: Json) =
    IO.fromEither {
      entityType match {
        case PullRequest.entityType => PullRequestState.serializer.codec.decodeJson(json).map(_.id)
        case Release.entityType     => Release.serializer.codec.decodeJson(json).map(_.id)
        case _                      => Left(DecodingFailure(s"No decoding is available for entity type $entityType", List.empty))
      }
    }

  test("Setting up the state log") {
    {
      for {
        _ <- prStore.unsafeSave(prState11) //1
        _ <- prStore.unsafeSave(prState12) //2
        _ <- releaseStore.unsafeSave(release11) //3
        _ <- prStore.unsafeSave(prState21) //4
        _ <- prStore.unsafeSave(prState34) //5
        _ <- prStore.unsafeSave(prState11, customTag) //6
        _ <- prStore.unsafeSave(prState13) //7
        _ <- releaseStore.unsafeSave(release12) //8
        _ <- releaseStore.unsafeSave(release12, customTag) //9
        _ <- prStore.unsafeSave(prState13, customTag) //10
        _ <- TombstoneStore.save(PullRequest.entityType, prState13, customTag) //11
        _ <- prStore.unsafeSave(prState12, customTag) //12
        _ <- releaseStore.unsafeSave(release21) //13
        _ <- TombstoneStore.save(PullRequest.entityType, prState11, customTag) //14
        _ <- prStore.unsafeSave(prState14) //15
        _ <- TombstoneStore.save(Release.entityType, release12, customTag) //16
        _ <- prStore.unsafeSave(prState14, customTag) //17
      } yield ()
    }.transact(xas.write)
  }

  /** Returns streams that returns elems of Iri and elem of unit */
  private def stream(project: ProjectRef, start: Offset, selectFilter: SelectFilter) = (
    StreamingQuery.elems[Iri](project, start, selectFilter, qc, xas, decodeValue),
    StreamingQuery.elems(project, start, selectFilter, qc, xas)
  )

  test("Running a stream on latest states on project 1 from the beginning") {
    val (iri, void) = stream(project1, Offset.start, SelectFilter.latest)

    val expected = List(
      SuccessElem(PullRequest.entityType, id1, Some(project1), Instant.EPOCH, Offset.at(1L), id1, rev),
      SuccessElem(PullRequest.entityType, id2, Some(project1), Instant.EPOCH, Offset.at(2L), id2, rev),
      SuccessElem(Release.entityType, release11.id, Some(project1), Instant.EPOCH, Offset.at(3L), release11.id, rev),
      SuccessElem(PullRequest.entityType, id3, Some(project1), Instant.EPOCH, Offset.at(7L), id3, rev),
      SuccessElem(Release.entityType, release12.id, Some(project1), Instant.EPOCH, Offset.at(8L), release12.id, rev),
      SuccessElem(PullRequest.entityType, id4, Some(project1), Instant.EPOCH, Offset.at(15L), id4, rev)
    )

    iri.compile.toList.assertEquals(expected)
    void.compile.toList.assertEquals(expected.map(_.void))
  }

  test("Running a stream on latest states on project 1 from offset 3") {
    val (iri, void) = stream(project1, Offset.at(3L), SelectFilter.latest)

    val expected = List(
      SuccessElem(PullRequest.entityType, id3, Some(project1), Instant.EPOCH, Offset.at(7L), id3, rev),
      SuccessElem(Release.entityType, release12.id, Some(project1), Instant.EPOCH, Offset.at(8L), release12.id, rev),
      SuccessElem(PullRequest.entityType, id4, Some(project1), Instant.EPOCH, Offset.at(15L), id4, rev)
    )

    iri.compile.toList.assertEquals(expected)
    void.compile.toList.assertEquals(expected.map(_.void))
  }

  test("Running a stream on latest states on project 1 from the beginning, filtering for types") {
    val (iri, void) = stream(project1, Offset.start, SelectFilter(Set(nxv + "Fix", nxv + "Feature"), Tag.Latest))

    val expected = List(
      SuccessElem(PullRequest.entityType, id3, Some(project1), Instant.EPOCH, Offset.at(7L), id3, rev),
      SuccessElem(PullRequest.entityType, id4, Some(project1), Instant.EPOCH, Offset.at(15L), id4, rev)
    )

    iri.compile.toList.assertEquals(expected)
    void.compile.toList.assertEquals(expected.map(_.void))
  }

  test(s"Running a stream on states with tag '${customTag.value}' on project 1 from the beginning") {
    val (iri, void) = stream(project1, Offset.start, SelectFilter.tag(customTag))

    val expected = List(
      SuccessElem(PullRequest.entityType, id1, Some(project1), Instant.EPOCH, Offset.at(6L), id1, rev),
      SuccessElem(Release.entityType, release12.id, Some(project1), Instant.EPOCH, Offset.at(9L), release12.id, rev),
      DroppedElem(PullRequest.entityType, id3, Some(project1), Instant.EPOCH, Offset.at(11L), -1),
      SuccessElem(PullRequest.entityType, id2, Some(project1), Instant.EPOCH, Offset.at(12L), id2, rev),
      DroppedElem(PullRequest.entityType, id1, Some(project1), Instant.EPOCH, Offset.at(14L), -1),
      DroppedElem(Release.entityType, release12.id, Some(project1), Instant.EPOCH, Offset.at(16L), -1),
      SuccessElem(PullRequest.entityType, id4, Some(project1), Instant.EPOCH, Offset.at(17L), id4, rev)
    )

    iri.compile.toList.assertEquals(expected)
    void.compile.toList.assertEquals(expected.map(_.void))
  }

  test(s"Running a stream on states with tag '${customTag.value}' on project 1 from offset 11") {
    val (iri, void) = stream(project1, Offset.at(11L), SelectFilter.tag(customTag))
    val expected    = List(
      SuccessElem(PullRequest.entityType, id2, Some(project1), Instant.EPOCH, Offset.at(12L), id2, rev),
      DroppedElem(PullRequest.entityType, id1, Some(project1), Instant.EPOCH, Offset.at(14L), -1),
      DroppedElem(Release.entityType, release12.id, Some(project1), Instant.EPOCH, Offset.at(16L), -1),
      SuccessElem(PullRequest.entityType, id4, Some(project1), Instant.EPOCH, Offset.at(17L), id4, rev)
    )

    iri.compile.toList.assertEquals(expected)
    void.compile.toList.assertEquals(expected.map(_.void))
  }

  test("Running a stream on latest states on project 1 from the beginning with an incomplete decode function") {
    def decodingFailure(entityType: EntityType)              =
      DecodingFailure(s"No decoding is available for entity type $entityType", List.empty)
    def incompleteDecode(entityType: EntityType, json: Json) =
      IO.fromEither {
        entityType match {
          case PullRequest.entityType => PullRequestState.serializer.codec.decodeJson(json).map(_.id)
          case _                      => Left(decodingFailure(entityType))
        }
      }

    val result = StreamingQuery.elems[Iri](project1, Offset.start, SelectFilter.latest, qc, xas, incompleteDecode)
    result.compile.toList.assertEquals(
      List(
        SuccessElem(PullRequest.entityType, id1, Some(project1), Instant.EPOCH, Offset.at(1L), id1, rev),
        SuccessElem(PullRequest.entityType, id2, Some(project1), Instant.EPOCH, Offset.at(2L), id2, rev),
        FailedElem(
          Release.entityType,
          release11.id,
          Some(project1),
          Instant.EPOCH,
          Offset.at(3L),
          decodingFailure(Release.entityType),
          rev
        ),
        SuccessElem(PullRequest.entityType, id3, Some(project1), Instant.EPOCH, Offset.at(7L), id3, rev),
        FailedElem(
          Release.entityType,
          release12.id,
          Some(project1),
          Instant.EPOCH,
          Offset.at(8L),
          decodingFailure(Release.entityType),
          rev
        ),
        SuccessElem(PullRequest.entityType, id4, Some(project1), Instant.EPOCH, Offset.at(15L), id4, rev)
      )
    )
  }

  test("Get the remaining elems for project 1 on latest from the beginning") {
    StreamingQuery
      .remaining(project1, SelectFilter.latest, Offset.start, xas)
      .assertEquals(
        Some(
          RemainingElems(6L, Instant.EPOCH)
        )
      )
  }

  test("Get the remaining elems for project 1 on latest from offset 6") {
    StreamingQuery
      .remaining(project1, SelectFilter.latest, Offset.at(6L), xas)
      .assertEquals(
        Some(
          RemainingElems(3L, Instant.EPOCH)
        )
      )
  }

  test(s"Get the remaining elems for project 1 on tag $customTag from the beginning") {
    StreamingQuery
      .remaining(project1, SelectFilter.tag(customTag), Offset.at(6L), xas)
      .assertEquals(
        Some(
          RemainingElems(4L, Instant.EPOCH)
        )
      )
  }

  test(s"Get no remaining for an unknown project") {
    StreamingQuery
      .remaining(ProjectRef.unsafe("xxx", "xxx"), SelectFilter.latest, Offset.at(6L), xas)
      .assertEquals(None)
  }

  test("Should only keep the last elem when elems with the same id appear several times") {
    val input    = List(1 -> "A", 2 -> "B", 3 -> "C", 1 -> "D", 2 -> "E", 1 -> "F")
    val expected = Chunk(3 -> "C", 2 -> "E", 1 -> "F")
    val obtained = StreamingQuery.dropDuplicates[(Int, String), Int](input, _._1)
    assertEquals(obtained, expected)
  }
}

object StreamingQuerySuite {

  final private case class Release(
      id: Iri,
      project: ProjectRef,
      rev: Int,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends ScopedState {
    override def deprecated: Boolean        = false
    override def schema: ResourceRef        = Latest(schemas + "release.json")
    override def types: Set[IriOrBNode.Iri] = Set(nxv + "Release")
  }

  private object Release {

    val entityType: EntityType = EntityType("release")

    @nowarn("cat=unused")
    val serializer: Serializer[Iri, Release] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration   = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[Release] = deriveConfiguredCodec[Release]
      Serializer()
    }
  }
}
