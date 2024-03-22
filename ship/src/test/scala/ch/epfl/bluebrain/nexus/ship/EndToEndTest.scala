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
import ch.epfl.bluebrain.nexus.tests.admin.ProjectPayload
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

      val (project, _, projectJson) = thereIsAProject()

      whenTheExportIsRunOnProject(project)

      theOldProjectIsDeleted(project)

      weRunTheImporter(project)

      weFixThePermissions(project)

      thereShouldBeAProject(project, projectJson)
    }

    "transfer multiple revisions of a project" in {

      val (project, payload, projectJson) = thereIsAProject()

      val updatedProjectJson = projectIsUpdated(project, payload.copy(description = "updated description"))

      whenTheExportIsRunOnProject(project)

      theOldProjectIsDeleted(project, 2)

      weRunTheImporter(project)

      weFixThePermissions(project)

      thereShouldBeAProject(project, updatedProjectJson)
      thereShouldBeAProjectRevision(project, 1, projectJson)
    }

    "transfer the default resolver" in {
      val (project, _, _)          = thereIsAProject()
      val defaultInProjectResolver = nxv + "defaultInProject"
      val (_, resolverJson)        = thereIsAResolver(defaultInProjectResolver, project)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporter(project)
      weFixThePermissions(project)

      thereShouldBeAResolver(project, defaultInProjectResolver, resolverJson)
    }

    "transfer a generic resource" in {
      val (project, _, _)          = thereIsAProject()
      val (resource, resourceJson) = thereIsAResource(project)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporter(project)
      weFixThePermissions(project)

      thereShouldBeAResource(project, resource, resourceJson)
    }

    "transfer a schema" in {
      val (project, _, _)      = thereIsAProject()
      val (schema, schemaJson) = thereIsASchema(project)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporter(project)
      weFixThePermissions(project)

      thereShouldBeASchema(project, schema, schemaJson)
    }

    def thereIsAProject(): (ProjectRef, ProjectPayload, Json) = {
      val orgName  = genString()
      val projName = genString()

      createOrg(writer, orgName).accepted

      val payload = ProjectPayload.generate(s"$orgName/$projName")
      adminDsl.createProject(orgName, projName, payload, writer).accepted

      val ref = ProjectRef.unsafe(orgName, projName)

      (ref, payload, fetchProjectState(ref))
    }

    def fetchProjectState(project: ProjectRef) = {
      val (projectJson, status) =
        deltaClient.getJsonAndStatus(s"/projects/${project.organization}/${project.project}", writer).accepted
      status shouldEqual StatusCodes.OK
      projectJson
    }

    def projectIsUpdated(ref: ProjectRef, projectPayload: ProjectPayload): Json = {
      adminDsl.updateProject(ref.organization.value, ref.project.value, projectPayload, writer, 1).accepted
      fetchProjectState(ref)
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

      IO.sleep(6.seconds).accepted
    }

    def theOldProjectIsDeleted(project: ProjectRef, rev: Int = 1): Unit = {
      deltaClient
        .delete[Json](s"/projects/${project.organization}/${project.project}?rev=$rev&prune=true", writer) {
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

    def thereIsAResource(project: ProjectRef): (Iri, Json) = {
      val resource               = nxv + genString()
      val encodedResource        = UrlUtils.encode(resource.toString)
      val body                   = json"""{"hello": "world"}"""
      deltaClient
        .put[Json](s"/resources/${project.organization}/${project.project}/_/$encodedResource", body, writer) {
          (_, response) =>
            response.status shouldEqual StatusCodes.Created
        }
        .accepted
      val (resourceJson, status) = deltaClient
        .getJsonAndStatus(s"/resources/${project.organization}/${project.project}/_/$encodedResource", writer)
        .accepted
      status shouldEqual StatusCodes.OK
      resource -> resourceJson
    }

    def thereShouldBeAResource(project: ProjectRef, resource: Iri, originalJson: Json): Assertion = {
      val encodedResolver = UrlUtils.encode(resource.toString)
      deltaClient
        .get[Json](s"/resources/${project.organization}/${project.project}/_/$encodedResolver", writer) {
          (json, response) =>
            {
              response.status shouldEqual StatusCodes.OK
              json shouldEqual originalJson
            }
        }
        .accepted
    }

    def thereIsASchema(project: ProjectRef): (Iri, Json) = {
      val schema                 = nxv + genString()
      val encodedSchema          = UrlUtils.encode(schema.toString)
      // TODO: Review the json of the simpleSchema
      val simpleSchema           =
        json"""{"shapes":[{"@id":"http://example.com/MyShape","@type":"http://www.w3.org/ns/shacl#NodeShape","nodeKind":"http://www.w3.org/ns/shacl#BlankNodeOrIRI","targetClass":"http://example.com/Custom","property":[{"path":"http://example.com/name","datatype":"http://www.w3.org/2001/XMLSchema#string","minCount":1}]}]}"""
      deltaClient
        .put[Json](s"/schemas/${project.organization}/${project.project}/$encodedSchema", simpleSchema, writer) {
          (_, response) =>
            response.status shouldEqual StatusCodes.Created
        }
        .accepted
      val (resourceJson, status) = deltaClient
        .getJsonAndStatus(s"/schemas/${project.organization}/${project.project}/$encodedSchema", writer)
        .accepted
      status shouldEqual StatusCodes.OK
      schema -> resourceJson
    }

    def thereShouldBeASchema(project: ProjectRef, schema: Iri, originalJson: Json): Assertion = {
      val encodedIri = UrlUtils.encode(schema.toString)
      deltaClient
        .get[Json](s"/schemas/${project.organization}/${project.project}/$encodedIri", writer) { (json, response) =>
          {
            response.status shouldEqual StatusCodes.OK
            json shouldEqual originalJson
          }
        }
        .accepted
    }
    def thereShouldBeAProjectRevision(project: ProjectRef, rev: Int, expectedProjectJson: Json): Assertion = {
      deltaClient
        .get[Json](s"/projects/${project.organization}/${project.project}?rev=$rev", writer) { (json, response) =>
          {
            response.status shouldEqual StatusCodes.OK
            json shouldEqual expectedProjectJson
          }
        }
        .accepted
    }
  }
}
