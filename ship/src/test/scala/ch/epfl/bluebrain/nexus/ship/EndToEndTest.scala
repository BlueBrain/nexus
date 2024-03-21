package ch.epfl.bluebrain.nexus.ship

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.ExportEventQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec
import ch.epfl.bluebrain.nexus.tests.Identity.writer
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Export
import io.circe.Json
import io.circe.syntax.EncoderOps
import org.scalatest.Assertion

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.IteratorHasAsScala

class EndToEndTest extends BaseIntegrationSpec {

  override def beforeAll(): Unit = {
    super.beforeAll()
    aclDsl.addPermission(s"/", writer, Export.Run).accepted
    ()
  }

  "The ship" should {

    "transfer a project" in {

      val (project, projectJson) = thereIsAProject()

      whenTheExportIsRunOnProject(project)

      theOldProjectIsDeleted(project)

      weRunTheImporter(project)

      weFixThePermissions(project)

      thereShouldBeAProject(project, projectJson)
    }

    "transfer a resolver" in {
      val (project, _)             = thereIsAProject()
      val defaultInProjectResolver = nxv + "defaultInProject"
      val (_, resolverJson)        = thereIsAResolver(defaultInProjectResolver, project)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporter(project)
      weFixThePermissions(project)

      thereShouldBeAResolver(project, defaultInProjectResolver, resolverJson)
    }

    def thereIsAProject(): (ProjectRef, Json) = {
      val project: ProjectRef   = ProjectRef.unsafe(genString(), genString())
      createProjects(writer, project.organization.value, project.project.value).accepted
      val (projectJson, status) =
        deltaClient.getJsonAndStatus(s"/projects/${project.organization}/${project.project}", writer).accepted
      status shouldEqual StatusCodes.OK
      project -> projectJson
    }

    def whenTheExportIsRunOnProject(project: ProjectRef): Unit = {
      val query = ExportEventQuery(
        Label.unsafe(project.project.value),
        NonEmptyList.of(project),
        Offset.start
      ).asJson

      deltaClient
        .post[Json]("/export/events", query, writer) { (_, response) =>
          response.status shouldEqual StatusCodes.Accepted
        }
        .accepted

      IO.sleep(5.seconds).accepted
    }

    def theOldProjectIsDeleted(project: ProjectRef): Unit = {
      deltaClient
        .delete[Json](s"/projects/${project.organization}/${project.project}?rev=1&prune=true", writer) {
          (_, response) => response.status shouldEqual StatusCodes.OK
        }
        .accepted

      eventually {
        deltaClient.get[Json](s"/projects/${project.organization}/${project.project}", writer) { (_, response) =>
          response.status shouldEqual StatusCodes.NotFound
        }
      }
      ()
    }

    def weRunTheImporter(project: ProjectRef): Unit = {
      val folder     = s"/tmp/ship/${project.project.value}/"
      val folderPath = Paths.get(folder)
      val file       = Files.newDirectoryStream(folderPath, "*.json").iterator().asScala.toList.head

      new RunShip().run(fs2.io.file.Path.fromNioPath(file), None).accepted
      ()
    }

    def thereShouldBeAProject(project: ProjectRef, originalJson: Json): Assertion = {
      deltaClient
        .get[Json](s"/projects/${project.organization}/${project.project}", writer) { (json, response) =>
          {
            response.status shouldEqual StatusCodes.OK
            json shouldEqual originalJson
          }
        }
        .accepted
    }

    def weFixThePermissions(project: ProjectRef) =
      aclDsl.addPermissions(s"/$project", writer, Permission.minimalPermissions).accepted

    def thereIsAResolver(resolver: Iri, project: ProjectRef): (Iri, Json) = {
      val encodedResolver        = UrlUtils.encode(resolver.toString)
      val (resolverJson, status) = deltaClient
        .getJsonAndStatus(s"/resolvers/${project.organization}/${project.project}/$encodedResolver", writer)
        .accepted
      status shouldEqual StatusCodes.OK
      resolver -> resolverJson
    }

    def thereShouldBeAResolver(project: ProjectRef, resolver: Iri, originalJson: Json): Assertion = {
      val encodedResolver = UrlUtils.encode(resolver.toString)
      deltaClient
        .get[Json](s"/resolvers/${project.organization}/${project.project}/$encodedResolver", writer) {
          (json, response) =>
            {
              response.status shouldEqual StatusCodes.OK
              json shouldEqual originalJson
            }
        }
        .accepted
    }
  }

}
