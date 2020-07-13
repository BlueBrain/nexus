package ch.epfl.bluebrain.nexus.kg.directives

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.organizations.{Organization, OrganizationResource}
import ch.epfl.bluebrain.nexus.admin.projects.{Project, ProjectResource}
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.kg.Error._
import ch.epfl.bluebrain.nexus.kg.KgError.{OrganizationNotFound, ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.kg.config.Schemas
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.{BeforeAndAfter, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectivesSpec.MappingValue
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.OrganizationRef
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.routes.KgRoutes
import ch.epfl.bluebrain.nexus.kg.{Error, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.util.EitherValues
import io.circe.Decoder
import io.circe.generic.auto._
import monix.eval.Task

//noinspection NameBooleanParameters
class ProjectDirectivesSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValues
    with IdiomaticMockito
    with BeforeAndAfter
    with OptionValues
    with ScalatestRouteTest
    with TestHelper {

  implicit private val appConfig: AppConfig = Settings(system).appConfig
  implicit private val http: HttpConfig     = appConfig.http

  implicit private val projectCache: ProjectCache[Task]  = mock[ProjectCache[Task]]
  implicit private val orgCache: OrganizationCache[Task] = mock[OrganizationCache[Task]]
  before {
    Mockito.reset(projectCache, orgCache)
  }

  private val id = genIri

  implicit private val orgDecoder: Decoder[OrganizationResource] =
    Decoder.instance { hc =>
      for {
        id          <- hc.get[AbsoluteIri]("@id")
        description <- hc.getOrElse[Option[String]]("description")(None)
        label       <- hc.get[String]("_label")
        uuid        <- hc.get[String]("_uuid").map(UUID.fromString)
        rev         <- hc.get[Long]("_rev")
        deprecated  <- hc.get[Boolean]("_deprecated")
        createdBy   <- hc.get[AbsoluteIri]("_createdBy")
        createdAt   <- hc.get[Instant]("_createdAt")
        updatedBy   <- hc.get[AbsoluteIri]("_updatedBy")
        updatedAt   <- hc.get[Instant]("_updatedAt")
        cBySubject   = Identity(createdBy).value.asInstanceOf[Subject]
        uBySubject   = Identity(updatedBy).value.asInstanceOf[Subject]
      } yield ResourceF(
        id,
        uuid,
        rev,
        deprecated,
        Set.empty,
        createdAt,
        cBySubject,
        updatedAt,
        uBySubject,
        Organization(label, description)
      )
    }

  implicit private val projectDecoder: Decoder[ProjectResource] =
    Decoder.instance { hc =>
      for {
        id               <- hc.get[AbsoluteIri]("@id")
        organization     <- hc.get[String]("_organizationLabel")
        description      <- hc.getOrElse[Option[String]]("_description")(None)
        base             <- hc.get[AbsoluteIri]("base")
        vocab            <- hc.get[AbsoluteIri]("vocab")
        apiMap           <- hc.get[List[MappingValue]]("apiMappings")
        label            <- hc.get[String]("_label")
        uuid             <- hc.get[String]("_uuid").map(UUID.fromString)
        organizationUuid <- hc.get[String]("_organizationUuid").map(UUID.fromString)
        rev              <- hc.get[Long]("_rev")
        deprecated       <- hc.get[Boolean]("_deprecated")
        createdBy        <- hc.get[AbsoluteIri]("_createdBy")
        createdAt        <- hc.get[Instant]("_createdAt")
        updatedBy        <- hc.get[AbsoluteIri]("_updatedBy")
        updatedAt        <- hc.get[Instant]("_updatedAt")
        cBySubject        = Identity(createdBy).value.asInstanceOf[Subject]
        uBySubject        = Identity(updatedBy).value.asInstanceOf[Subject]
        apiMapping        = apiMap.map(v => v.prefix -> v.namespace).toMap
      } yield ResourceF(
        id,
        uuid,
        rev,
        deprecated,
        Set.empty,
        createdAt,
        cBySubject,
        updatedAt,
        uBySubject,
        Project(label, organizationUuid, organization, description, apiMapping, base, vocab)
      )

    }

  "A Project directives" when {

    val label       = ProjectLabel("organization", "project")
    val apiMappings = Map[String, AbsoluteIri](
      "nxv"           -> nxv.base,
      "resource"      -> Schemas.unconstrainedSchemaUri,
      "elasticsearch" -> nxv.defaultElasticSearchIndex.value,
      "graph"         -> nxv.defaultSparqlIndex.value
    )

    // format: off
    val projectMeta = ResourceF(id, genUUID, 1, false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, Project("project", genUUID, "organization", None, apiMappings, nxv.projects.value, genIri))
    val orgMeta     = ResourceF(id, genUUID, 1, false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, Organization("organization", None))
    // format: on

    val apiMappingsFinal = Map[String, AbsoluteIri](
      "resource"        -> Schemas.unconstrainedSchemaUri,
      "schema"          -> Schemas.shaclSchemaUri,
      "view"            -> Schemas.viewSchemaUri,
      "resolver"        -> Schemas.resolverSchemaUri,
      "file"            -> Schemas.fileSchemaUri,
      "storage"         -> Schemas.storageSchemaUri,
      "nxv"             -> nxv.base,
      "documents"       -> nxv.defaultElasticSearchIndex.value,
      "graph"           -> nxv.defaultSparqlIndex.value,
      "defaultResolver" -> nxv.defaultResolver.value,
      "defaultStorage"  -> nxv.defaultStorage.value
    )

    val projectMetaResp =
      projectMeta.copy(value = projectMeta.value.copy(apiMappings = projectMeta.value.apiMappings ++ apiMappingsFinal))

    "dealing with organizations" should {

      def route(label: String): Route = {
        import monix.execution.Scheduler.Implicits.global
        KgRoutes.wrap(
          (get & org(label)) { o =>
            complete(StatusCodes.OK -> o)
          }
        )
      }

      "fetch the organization by label" in {

        orgCache.getBy("organization") shouldReturn Task.pure(Option(orgMeta))

        Get("/") ~> route("organization") ~> check {
          responseAs[OrganizationResource] shouldEqual orgMeta
        }
      }

      "fetch the organization by UUID" in {

        orgCache.get(orgMeta.uuid) shouldReturn Task.pure(Option(orgMeta))

        Get("/") ~> route(orgMeta.uuid.toString) ~> check {
          responseAs[OrganizationResource] shouldEqual orgMeta
        }
      }

      "fetch the organization by label when not found from UUID" in {

        orgCache.get(orgMeta.uuid) shouldReturn Task.pure(None)
        orgCache.getBy(orgMeta.uuid.toString) shouldReturn Task.pure(Option(orgMeta))

        Get("/") ~> route(orgMeta.uuid.toString) ~> check {
          responseAs[OrganizationResource] shouldEqual orgMeta
        }
      }

      "reject organization when not found" in {
        orgCache.getBy("organization") shouldReturn Task.pure(None)

        Get("/") ~> route("organization") ~> check {
          status shouldEqual StatusCodes.NotFound
          responseAs[Error].tpe shouldEqual classNameOf[OrganizationNotFound]
        }
      }
    }

    "dealing with project" should {

      def route: Route = {
        import monix.execution.Scheduler.Implicits.global
        KgRoutes.wrap(
          (get & project) { project =>
            complete(StatusCodes.OK -> project)
          }
        )
      }

      def notDeprecatedRoute(implicit proj: ProjectResource): Route =
        KgRoutes.wrap(
          (get & projectNotDeprecated) {
            complete(StatusCodes.OK)
          }
        )

      val orgRef     = OrganizationRef(projectMeta.value.organizationUuid)
      val projectRef = ProjectRef(projectMeta.uuid)

      "fetch the project by label" in {

        projectCache.getBy(label) shouldReturn Task.pure(Option(projectMeta))

        Get("/organization/project") ~> route ~> check {
          responseAs[ProjectResource] shouldEqual projectMetaResp
        }
      }

      "fetch the project by UUID" in {

        projectCache.get(projectMeta.value.organizationUuid, projectMeta.uuid) shouldReturn Task.pure(
          Option(projectMeta)
        )

        Get(s"/${orgRef.show}/${projectRef.show}") ~> route ~> check {
          responseAs[ProjectResource] shouldEqual projectMetaResp
        }
      }

      "reject project when not found" in {
        projectCache.getBy(label) shouldReturn Task.pure(None)

        Get("/organization/project") ~> route ~> check {
          status shouldEqual StatusCodes.NotFound
          responseAs[Error].tpe shouldEqual classNameOf[ProjectNotFound]
        }
      }

      "pass when available project is not deprecated" in {
        Get("/") ~> notDeprecatedRoute(projectMeta) ~> check {
          status shouldEqual StatusCodes.OK
        }
      }

      "reject when available project is deprecated" in {
        Get("/") ~> notDeprecatedRoute(projectMeta.copy(deprecated = true)) ~> check {
          status shouldEqual StatusCodes.BadRequest
          responseAs[Error].tpe shouldEqual classNameOf[ProjectIsDeprecated]
        }
      }
    }
  }
}

object ProjectDirectivesSpec {
  final case class MappingValue(prefix: String, namespace: AbsoluteIri)
}
