package ch.epfl.bluebrain.nexus.ship

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewType.AggregateBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{defaultViewId => bgDefaultViewId}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewType.AggregateElasticSearch
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId => esDefaultViewId}
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.{defaultViewId => searchViewId}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.ExportEventQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.ShipCommand.RunCommand
import ch.epfl.bluebrain.nexus.ship.config.InputConfig.ProjectMapping
import ch.epfl.bluebrain.nexus.tests.Identity.writer
import ch.epfl.bluebrain.nexus.tests.admin.ProjectPayload
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Export
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Optics}
import fs2.io.file.Path
import io.circe.Json
import io.circe.optics.JsonPath.root
import io.circe.syntax.EncoderOps
import org.scalatest.Assertion

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.IteratorHasAsScala

class ShipIntegrationSpec extends BaseIntegrationSpec {

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

      thereShouldBeAViewWithId(project, bgDefaultViewId)
      thereShouldBeAViewWithId(project, esDefaultViewId)
      thereShouldBeAViewWithId(project, searchViewId)
    }

    "transfer multiple revisions of a project" in {

      val (project, revisionsAndStates) = thereAreManyRevisionsOfAProject()

      whenTheExportIsRunOnProject(project)

      theOldProjectIsDeleted(project, revisionsAndStates.keys.max)

      weRunTheImporter(project)

      weFixThePermissions(project)

      thereShouldBeAProjectThatMatchesExpectations(project, revisionsAndStates)
    }

    "transfer the default resolver" in {
      val (project, _, _)          = thereIsAProject()
      val defaultInProjectResolver = nxv + "defaultInProject"
      val (_, resolverJson)        = thereIsADefaultResolver(defaultInProjectResolver, project)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporter(project)
      weFixThePermissions(project)

      thereShouldBeAResolver(project, defaultInProjectResolver, resolverJson)
    }

    "transfer all events from a resolver" in {
      val (project, id, revisionsAndStates) = thereAreManyRevisionsOfAResolver()

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)
      weRunTheImporter(project)
      weFixThePermissions(project)

      thereShouldBeAResolverThatMatchesExpectations(project, id, revisionsAndStates)
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

    "transfer an elasticsearch view" in {
      val (project, _, _)      = thereIsAProject()
      val (esView, esViewJson) = thereIsAnElasticSearchView(project)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporter(project)
      weFixThePermissions(project)

      thereShouldBeAView(project, esView, esViewJson)
    }

    "transfer a blazegraph view" in {
      val (project, _, _)      = thereIsAProject()
      val (bgView, bgViewJson) = thereIsABlazegraphView(project)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporter(project)
      weFixThePermissions(project)

      thereShouldBeAView(project, bgView, bgViewJson)
    }

    "transfer an aggregated elasticsearch view" in {
      val (project, _, _)          = thereIsAProject()
      val (sourceProj1, _, _)      = thereIsAProject()
      val (sourceProj2, _, _)      = thereIsAProject()
      val (targetProj1, _, _)      = thereIsAProject()
      val (targetProj2, _, _)      = thereIsAProject()
      val (esView, originalSource) = thereIsAnAggregateElasticSearchView(project, List(sourceProj1, sourceProj2))
      val mapping                  = Map(sourceProj1 -> targetProj1, sourceProj2 -> targetProj2)
      val patchedSource            = patchedAggregateViewSource(originalSource, mapping)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporterWithProjectMapping(project, mapping)
      weFixThePermissions(project)

      thereShouldBeAView(project, esView, patchedSource)
    }

    "transfer an aggregated blazegraph view" in {
      val (project, _, _)          = thereIsAProject()
      val (sourceProj1, _, _)      = thereIsAProject()
      val (sourceProj2, _, _)      = thereIsAProject()
      val (targetProj1, _, _)      = thereIsAProject()
      val (targetProj2, _, _)      = thereIsAProject()
      val (bgView, originalSource) = thereIsAnAggregateBlazegraphView(project, List(sourceProj1, sourceProj2))
      val mapping                  = Map(sourceProj1 -> targetProj1, sourceProj2 -> targetProj2)
      val patchedSource            = patchedAggregateViewSource(originalSource, mapping)

      whenTheExportIsRunOnProject(project)
      theOldProjectIsDeleted(project)

      weRunTheImporterWithProjectMapping(project, mapping)
      weFixThePermissions(project)

      thereShouldBeAView(project, bgView, patchedSource)
    }

    def thereShouldBeAViewWithId(project: ProjectRef, view: Iri): Assertion = {
      val encodedIri = UrlUtils.encode(view.toString)
      deltaClient
        .get[Json](s"/views/${project.organization}/${project.project}/$encodedIri", writer) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }
        .accepted
    }

    def thereShouldBeAView(project: ProjectRef, view: Iri, expectedJson: Json): Assertion = {
      val encodedIri = UrlUtils.encode(view.toString)
      deltaClient
        .get[Json](s"/views/${project.organization}/${project.project}/$encodedIri", writer) { (json, response) =>
          {
            response.status shouldEqual StatusCodes.OK
            json should equalIgnoreArrayOrder(expectedJson)
          }
        }
        .accepted
    }

    def thereIsABlazegraphView(project: ProjectRef): (Iri, Json) = {
      val simpleBgView = json"""{
        "@type": "SparqlView",
        "includeMetadata": true,
        "includeDeprecated": false,
        "resourceTag": "mytag"
      }"""
      thereIsAView(project, simpleBgView)
    }

    def patchedAggregateViewSource(original: Json, mapping: ProjectMapping): Json = {
      val mapViewProjects: Json => Json =
        root.views.each.project.string.modify(p => mapping(ProjectRef.parse(p).toOption.get).toString)
      mapViewProjects(original)
    }

    def aggregateViewSource(projects: List[ProjectRef], viewType: String, defaultViewId: Iri): Json = {
      val views: Json = projects.map(pr => json"""{"viewId": "$defaultViewId", "project": "$pr"}""").asJson

      json"""{
        "@type": ["View", "$viewType"],
        "views": $views
      }
      """
    }

    def thereIsAnAggregateBlazegraphView(project: ProjectRef, viewProjects: List[ProjectRef]): (Iri, Json) =
      thereIsAView(project, aggregateViewSource(viewProjects, AggregateBlazegraphView.toString, bgDefaultViewId))

    def thereIsAnAggregateElasticSearchView(project: ProjectRef, viewProjects: List[ProjectRef]): (Iri, Json) =
      thereIsAView(project, aggregateViewSource(viewProjects, AggregateElasticSearch.toString, esDefaultViewId))

    def thereIsAView(project: ProjectRef, body: Json): (Iri, Json) = {
      val view        = nxv + genString()
      val encodedView = UrlUtils.encode(view.toString)
      deltaClient
        .put[Json](s"/views/${project.organization}/${project.project}/$encodedView", body, writer) { (_, response) =>
          response.status shouldEqual StatusCodes.Created
        }
        .accepted

      val (viewJson, status) = deltaClient
        .getJsonAndStatus(s"/views/${project.organization}/${project.project}/$encodedView", writer)
        .accepted
      status shouldEqual StatusCodes.OK
      view -> viewJson
    }

    def thereIsAnElasticSearchView(project: ProjectRef): (Iri, Json) = {
      val simpleEsView =
        json"""
          {
              "@type": "ElasticSearchView",
              "resourceSchemas": [],
              "resourceTypes": [],
              "sourceAsText": false,
              "includeMetadata": true,
              "includeDeprecated": false,
              "mapping": {}
          }
            """
      thereIsAView(project, simpleEsView)
    }

    def thereAreManyRevisionsOfAProject(): (ProjectRef, Map[Int, Json]) = {
      val (ref, payload, json) = thereIsAProject()
      val updatedJson          = projectIsUpdated(ref, payload.copy(description = "updated description"), 1)
      val deprecatedJson       = projectIsDeprecated(ref, 2)
      val undeprecatedJson     = projectIsUndeprecated(ref, 3)
      (
        ref,
        Map(
          1 -> json,
          2 -> updatedJson,
          3 -> deprecatedJson,
          4 -> undeprecatedJson
        )
      )
    }

    def thereAreManyRevisionsOfAResolver(): (ProjectRef, String, Map[Int, Json]) = {
      val (project, _, _)     = thereIsAProject()
      val (id, payload, json) = thereIsAResolver(project)
      val updatedPayload      =
        payload.hcursor.downField("priority").set(3.asJson).top.getOrElse(fail("could not update payload"))
      val updatedJson         = resolverIsUpdated(project, id, updatedPayload, 1)
      val deprecatedJson      = resolverIsDeprecated(project, id, 2)
      (
        project,
        id,
        Map(
          1 -> json,
          2 -> updatedJson,
          3 -> deprecatedJson
        )
      )
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

    def projectIsUpdated(ref: ProjectRef, projectPayload: ProjectPayload, revision: Int): Json = {
      adminDsl.updateProject(ref.organization.value, ref.project.value, projectPayload, writer, revision).accepted
      fetchProjectState(ref)
    }

    def projectIsDeprecated(ref: ProjectRef, rev: Int): Json = {
      val (_, statusCode) =
        deltaClient.deleteJsonAndStatus(s"/projects/${ref.organization}/${ref.project}?rev=$rev", writer).accepted
      statusCode shouldBe StatusCodes.OK
      fetchProjectState(ref)
    }

    def projectIsUndeprecated(ref: ProjectRef, rev: Int): Json = {
      val (_, statusCode) = deltaClient
        .putJsonAndStatus(s"/projects/${ref.organization}/${ref.project}/undeprecate?rev=$rev", Json.obj(), writer)
        .accepted
      statusCode shouldBe StatusCodes.OK
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

    def weRunTheImporter(project: ProjectRef): Unit =
      weRunTheImporterWithConfig(project, None)

    def weRunTheImporterWithProjectMapping(project: ProjectRef, mapping: ProjectMapping): Unit = {
      val projectMappingConfPath = createProjectMappingConf(mapping)
      weRunTheImporterWithConfig(project, Some(projectMappingConfPath))
      Files.delete(projectMappingConfPath.toNioPath)
    }

    def createProjectMappingConf(mapping: ProjectMapping): Path = {
      val confPath    = Paths.get(s"/tmp/ship-conf.conf")
      val mappingConf = mapping.map { case (from, to) => s""" "$from": "$to" """ }.mkString("\n")
      Files.write(
        confPath,
        s"""
           |ship {
           |  input {
           |    project-mapping = {
           |      $mappingConf
           |    }
           |  }
           |}
           |""".stripMargin.getBytes
      )
      Path.fromNioPath(confPath)
    }

    def weRunTheImporterWithConfig(project: ProjectRef, config: Option[Path]): Unit = {
      val folder     = s"/tmp/ship/${project.project.value}/"
      val folderPath = Paths.get(folder)
      val file       = Files.newDirectoryStream(folderPath, "*.json").iterator().asScala.toList.head
      val r          = RunCommand(Path.fromNioPath(file), config, Offset.start, RunMode.Local)
      Main.run(r).accepted
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

    def thereShouldBeAProjectThatMatchesExpectations(project: ProjectRef, expectations: Map[Int, Json]): Assertion = {
      expectations.foreach { case (rev, expectedJson) =>
        thereShouldBeAProjectRevision(project, rev, expectedJson)
      }
      succeed
    }

    def thereShouldBeAResolverThatMatchesExpectations(
        project: ProjectRef,
        id: String,
        expectations: Map[Int, Json]
    ): Assertion = {
      expectations.foreach { case (rev, expectedJson) =>
        thereShouldBeAResolverRevision(project, id, rev, expectedJson)
      }
      succeed
    }

    def weFixThePermissions(project: ProjectRef) =
      aclDsl.addPermissions(s"/$project", writer, Permission.minimalPermissions).accepted

    def thereIsADefaultResolver(id: Iri, project: ProjectRef): (Iri, Json) = {
      val encodedResolver        = UrlUtils.encode(id.toString)
      val (resolverJson, status) = deltaClient
        .getJsonAndStatus(s"/resolvers/${project.organization}/${project.project}/$encodedResolver", writer)
        .accepted
      status shouldEqual StatusCodes.OK
      id -> resolverJson
    }

    def thereIsAResolver(project: ProjectRef): (String, Json, Json) = {
      val payload  = json"""{"@type": "InProject", "priority": 2}"""
      val response = deltaClient
        .postAndReturn[Json](s"/resolvers/${project.organization}/${project.project}", payload, writer) {
          case (_, response) => response.status shouldBe StatusCodes.Created
        }
        .accepted

      val id = Optics.`@id`.getOption(response).getOrElse(fail("resolver creation response did not contain an @id"))

      val (resolverJson, status) = deltaClient
        .getJsonAndStatus(resolverUrl(project, id), writer)
        .accepted
      status shouldEqual StatusCodes.OK
      (id, payload, resolverJson)
    }

    def resolverUrl(project: ProjectRef, id: String): String =
      s"/resolvers/${project.organization}/${project.project}/${UrlUtils.encode(id)}"

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

    def fetchResolver(project: ProjectRef, id: String): Json = {
      val (json, status) = deltaClient
        .getJsonAndStatus(s"/resolvers/${project.organization}/${project.project}/${UrlUtils.encode(id)}", writer)
        .accepted

      status shouldEqual StatusCodes.OK
      json
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

    def thereShouldBeAResolverRevision(
        project: ProjectRef,
        id: String,
        rev: Int,
        expectedProjectJson: Json
    ): Assertion = {
      deltaClient
        .get[Json](s"${resolverUrl(project, id)}?rev=$rev", writer) { (json, response) =>
          response.status shouldEqual StatusCodes.OK
          json shouldEqual expectedProjectJson
        }
        .accepted
    }

    def resolverIsUpdated(project: ProjectRef, id: String, updatedPayload: Json, rev: Int): Json = {
      deltaClient
        .put[Json](s"${resolverUrl(project, id)}?rev=$rev", updatedPayload, writer) { case (_, response) =>
          response.status shouldBe StatusCodes.OK
        }
        .accepted

      fetchResolver(project, id)
    }

    def resolverIsDeprecated(project: ProjectRef, id: String, rev: Int): Json = {
      deltaClient
        .delete[Json](s"${resolverUrl(project, id)}?rev=$rev", writer) { case (_, response) =>
          response.status shouldBe StatusCodes.OK
        }
        .accepted

      fetchResolver(project, id)
    }
  }
}
