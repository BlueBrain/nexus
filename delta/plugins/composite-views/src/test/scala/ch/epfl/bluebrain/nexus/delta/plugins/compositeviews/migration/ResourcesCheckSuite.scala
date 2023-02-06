package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.implicits._
import fs2.Stream
import io.circe.Json
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class ResourcesCheckSuite extends BioSuite with Doobie.Fixture with CirceLiteral {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)

  private lazy val xas = doobie()

  test("Should init the extra tables") {
    MigrationCheckHelper.initTables(xas)
  }

  test("Detect correctly the diffs") {
    val project1 = ProjectRef.unsafe("org", "proj1")
    val project2 = ProjectRef.unsafe("org", "proj2")

    val id1 = nxv + "1"
    val id2 = nxv + "2"
    val id3 = nxv + "3"
    val id4 = nxv + "4"

    val map = Map(
      project1 -> List(id1, id2),
      project2 -> List(id3, id4)
    )

    def fetchProjects = Stream.emits(List(project1, project2))

    def fetchElems(project: ProjectRef, offset: Offset) =
      (project, offset) match {
        case (_, o) if o.value > 1L => Stream.empty
        case (p, _)                 =>
          Stream.emits(map(p)).zipWithIndex.map { case (id, index) =>
            SuccessElem(
              tpe = Resources.entityType,
              id = id,
              project = Some(p),
              instant = Instant.EPOCH,
              offset = Offset.at(index + 1L),
              value = (),
              revision = 1
            )
          }
      }

    def fetch17(project: ProjectRef, id: Iri) =
      (project, id) match {
        case (_, `id1`) => Task.some(json"""{ "a": 5 }""")
        case (_, `id2`) => Task.some(json"""{ "b": [10,5,2] }""")
        case (_, `id3`) => Task.some(json"""{ "c": "ABC" }""")
        case (_, `id4`) => Task.raiseError(new IllegalArgumentException("Failed id4 v17"))
        case (_, _)     => Task.none
      }

    def fetch18(project: ProjectRef, id: Iri) =
      (project, id) match {
        case (_, `id1`) => Task.some(json"""{ "a": 5 }""")
        case (_, `id2`) => Task.some(json"""{ "b": [2,5,10] }""")
        case (_, `id3`) => Task.some(json"""{ "c": "DEF" }""")
        case (_, `id4`) => Task.some(json"""{ "a": 15 }""")
        case (_, _)     => Task.none
      }

    val check = new ResourcesCheck(fetchProjects, fetchElems, fetch17, fetch18, 1.second, xas)

    for {
      _ <- check.run.compile.drain
      _ <- checkDiff(project1, id1).assertNone
      _ <- checkDiff(project1, id2).assertNone
      _ <- check.fetchOffset(project1).eventually(Offset.at(2L))
      _ <- check.fetchOffset(project2).eventually(Offset.at(2L))
    } yield ()
  }

  private def checkDiff(project: ProjectRef, id: Iri) =
    sql"""SELECT value_1_7,  value_1_8, error FROM public.migration_resources_diff WHERE project = $project and id = $id"""
      .query[(Option[Json], Option[Json], String)]
      .option
      .transact(xas.read)

}
