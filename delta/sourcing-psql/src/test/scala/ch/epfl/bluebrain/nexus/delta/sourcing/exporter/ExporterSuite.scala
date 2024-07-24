package ch.epfl.bluebrain.nexus.delta.sourcing.exporter

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestMerged, PullRequestUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.utils.StreamingUtils
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.file.TempDirectory
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.io.file.{Files, Path}
import io.circe.{parser, DecodingFailure, JsonObject}
import munit.{AnyFixture, Location}

import java.time.Instant

class ExporterSuite extends NexusSuite with Doobie.Fixture with TempDirectory.Fixture with FixedClock {

  private lazy val doobieFixture                 = doobieInject(
    PullRequest.eventStore(_, event1, event2, event3, event4, event5, event6),
    Exporter(exporterConfig, _)
  )
  override def munitFixtures: Seq[AnyFixture[_]] = List(tempDirectory, doobieFixture)

  private lazy val exporterConfig   = ExportConfig(5, 4, 3, exportDirectory)
  private lazy val (_, _, exporter) = doobieFixture()
  private lazy val exportDirectory  = tempDirectory()

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj3")

  private val id1 = nxv + "1"
  private val id2 = nxv + "2"
  private val id3 = nxv + "3"

  private val event1 = PullRequestCreated(id1, project1, Instant.EPOCH, Anonymous)
  private val event2 = PullRequestUpdated(id1, project1, 2, Instant.EPOCH, User("Alice", Label.unsafe("Wonderland")))
  private val event3 = PullRequestMerged(id1, project1, 3, Instant.EPOCH, User("Alice", Label.unsafe("Wonderland")))

  private val event4 = PullRequestCreated(id2, project1, Instant.EPOCH, Anonymous)

  private val event5 = PullRequestCreated(id1, project2, Instant.EPOCH, Anonymous)

  private val event6 = PullRequestCreated(id3, project3, Instant.EPOCH, Anonymous)

  private def parseAsObject(value: String) =
    parser.parse(value).flatMap(_.asObject.toRight(DecodingFailure("Expected a json object", List.empty)))

  private def orderingValue(obj: JsonObject) =
    obj("ordering").flatMap(_.asNumber.flatMap(_.toInt))

  private def readDataFiles(path: Path): IO[(Int, List[JsonObject])] = {
    def readDataFile(path: Path) =
      StreamingUtils
        .readLines(path)
        .evalMap { line =>
          IO.fromEither(parseAsObject(line))
        }
        .compile
        .toList

    for {
      dataFiles  <- Files[IO].list(path).filter(_.extName.equals(".json")).compile.toList
      jsonEvents <- dataFiles.sortBy(_.fileName.toString).flatTraverse(readDataFile)
    } yield (dataFiles.size, jsonEvents)
  }

  private def readSuccess(path: Path) = Files[IO]
    .readUtf8(path)
    .evalMap { content =>
      IO.fromEither(parser.decode[ExportEventQuery](content))
    }
    .compile
    .lastOrError

  private def assertExport(
      result: Exporter.ExportResult,
      query: ExportEventQuery,
      expectedFileCount: Int,
      expectedOrdering: List[Int]
  )(implicit location: Location) =
    readDataFiles(result.targetDirectory).map { case (fileCount, exportContent) =>
      assertEquals(fileCount, expectedFileCount)
      val orderingValues = exportContent.mapFilter(orderingValue)
      assertEquals(orderingValues, expectedOrdering)
    } >>
      readSuccess(result.success).assertEquals(query)

  test(s"Export all events for $project1 and $project3") {
    val query = ExportEventQuery(Label.unsafe("export1"), NonEmptyList.of(project1, project3), Offset.start)
    for {
      result <- exporter.events(query)
      _      <- assertExport(result, query, 2, List(1, 2, 3, 4, 6))
    } yield ()
  }

  test(s"Export all events for $project1 and $project3 from offset 3") {
    val query = ExportEventQuery(Label.unsafe("export2"), NonEmptyList.of(project1, project3), Offset.at(2L))
    for {
      result <- exporter.events(query)
      _      <- assertExport(result, query, 1, List(3, 4, 6))
    } yield ()
  }
}
