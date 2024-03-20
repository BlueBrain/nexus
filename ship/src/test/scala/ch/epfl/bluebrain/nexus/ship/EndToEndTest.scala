package ch.epfl.bluebrain.nexus.ship

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.ExportEventQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.writer
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Export
import io.circe.Json
import io.circe.syntax.EncoderOps

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.IteratorHasAsScala

class EndToEndTest extends BaseIntegrationSpec {

  val project: ProjectRef = ProjectRef.unsafe(genString(), genString())

  override def beforeAll(): Unit = {
    super.beforeAll()
    aclDsl.addPermission(s"/", writer, Export.Run).accepted
    ()
  }

  "The ship" should {
    "transfer a project" in {
      val query = ExportEventQuery(
        Label.unsafe(project.project.value),
        NonEmptyList.of(project),
        Offset.start
      ).asJson

      val createProject = createProjects(writer, project.organization.value, project.project.value)

      val runExport     = deltaClient.post[Json]("/export/events", query, writer) { (_, response) =>
        response.status shouldEqual StatusCodes.Accepted
      }
      val deleteProject =
        deltaClient.delete[Json](s"/projects/${project.organization}/${project.project}?rev=1&prune=true", writer) {
          (_, response) => response.status shouldEqual StatusCodes.OK
        }
      val fetchProject  = deltaClient.get[Json](s"/projects/${project.organization}/${project.project}", writer) {
        (_, response) => response.status shouldEqual StatusCodes.NotFound
      }

      val findFile = IO.delay {
        val folder     = s"/tmp/ship/${project.project.value}/"
        val folderPath = Paths.get(folder)
        Files.newDirectoryStream(folderPath, "*.json").iterator().asScala.toList.head
      }

      val runShip = findFile
        .flatMap { filePath =>
          new RunShip().run(fs2.io.file.Path.fromNioPath(filePath), None)
        }
        .map { _ => 1 shouldEqual 1 }

      val setPermissions = aclDsl.addPermissions(s"/$project", writer, Permission.minimalPermissions)

      val fetchProjectSuccess = deltaClient.get[Json](s"/projects/${project.organization}/${project.project}", writer) {
        (_, response) => response.status shouldEqual StatusCodes.OK
      }

      (createProject >> runExport >> IO.sleep(6.seconds) >> deleteProject >> eventually {
        fetchProject
      } >> runShip >> setPermissions >> fetchProjectSuccess).accepted

    }
  }

}
