package ch.epfl.bluebrain.nexus.kg.directives

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.admin.client.types.{Organization, Project}
import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.iam.client.IamClientError
import ch.epfl.bluebrain.nexus.iam.client.types.AuthToken
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.kg.Error._
import ch.epfl.bluebrain.nexus.kg.KgError.{OrganizationNotFound, ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.config.{Schemas, Settings}
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.{OrganizationRef, ProjectInitializer}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.routes.Routes
import ch.epfl.bluebrain.nexus.kg.{Error, KgError, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
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
    with ScalatestRouteTest
    with TestHelper {

  private val appConfig                 = Settings(system).appConfig
  implicit private val http: HttpConfig = appConfig.http

  implicit private val projectCache: ProjectCache[Task]      = mock[ProjectCache[Task]]
  implicit private val client: AdminClient[Task]             = mock[AdminClient[Task]]
  implicit private val initializer: ProjectInitializer[Task] = mock[ProjectInitializer[Task]]
  implicit private val cred: Option[AuthToken]               = None

  before {
    Mockito.reset(projectCache, client, initializer)
  }

  private val id = genIri

  implicit private val orgDecoder: Decoder[Organization] =
    Decoder.instance { hc =>
      for {
        description <- hc.getOrElse[Option[String]]("description")(None)
        label       <- hc.get[String]("label")
        uuid        <- hc.get[String]("uuid").map(UUID.fromString)
        rev         <- hc.get[Long]("rev")
        deprecated  <- hc.get[Boolean]("deprecated")
        createdBy   <- hc.get[AbsoluteIri]("createdBy")
        createdAt   <- hc.get[Instant]("createdAt")
        updatedBy   <- hc.get[AbsoluteIri]("updatedBy")
        updatedAt   <- hc.get[Instant]("updatedAt")
      } yield Organization(id, label, description, uuid, rev, deprecated, createdAt, createdBy, updatedAt, updatedBy)
    }

  implicit private val projectDecoder: Decoder[Project] =
    Decoder.instance { hc =>
      for {
        organization     <- hc.get[String]("organizationLabel")
        description      <- hc.getOrElse[Option[String]]("description")(None)
        base             <- hc.get[AbsoluteIri]("base")
        vocab            <- hc.get[AbsoluteIri]("vocab")
        apiMap           <- hc.get[Map[String, AbsoluteIri]]("apiMappings")
        label            <- hc.get[String]("label")
        uuid             <- hc.get[String]("uuid").map(UUID.fromString)
        organizationUuid <- hc.get[String]("organizationUuid").map(UUID.fromString)
        rev              <- hc.get[Long]("rev")
        deprecated       <- hc.get[Boolean]("deprecated")
        createdBy        <- hc.get[AbsoluteIri]("createdBy")
        createdAt        <- hc.get[Instant]("createdAt")
        updatedBy        <- hc.get[AbsoluteIri]("updatedBy")
        updatedAt        <- hc.get[Instant]("updatedAt")
      } yield Project(
        id,
        label,
        organization,
        description,
        base,
        vocab,
        apiMap,
        uuid,
        organizationUuid,
        rev,
        deprecated,
        createdAt,
        createdBy,
        updatedAt,
        updatedBy
      )
    }

  "A Project directives" when {

    val creator = Iri.absolute("http://example.com/subject").rightValue

    val label       = ProjectLabel("organization", "project")
    val apiMappings = Map[String, AbsoluteIri](
      "nxv"           -> nxv.base,
      "resource"      -> Schemas.unconstrainedSchemaUri,
      "elasticsearch" -> nxv.defaultElasticSearchIndex,
      "graph"         -> nxv.defaultSparqlIndex
    )
    val projectMeta = Project(
      id,
      "project",
      "organization",
      None,
      nxv.projects,
      genIri,
      apiMappings,
      genUUID,
      genUUID,
      1L,
      false,
      Instant.EPOCH,
      creator,
      Instant.EPOCH,
      creator
    )

    implicit val subject: Subject = User("subject", "realm")

    val orgMeta =
      Organization(id, "organization", None, genUUID, 1L, false, Instant.EPOCH, creator, Instant.EPOCH, creator)

    val apiMappingsFinal = Map[String, AbsoluteIri](
      "resource"        -> Schemas.unconstrainedSchemaUri,
      "schema"          -> Schemas.shaclSchemaUri,
      "view"            -> Schemas.viewSchemaUri,
      "resolver"        -> Schemas.resolverSchemaUri,
      "file"            -> Schemas.fileSchemaUri,
      "storage"         -> Schemas.storageSchemaUri,
      "nxv"             -> nxv.base,
      "documents"       -> nxv.defaultElasticSearchIndex,
      "graph"           -> nxv.defaultSparqlIndex,
      "defaultResolver" -> nxv.defaultResolver,
      "defaultStorage"  -> nxv.defaultStorage
    )

    val projectMetaResp =
      projectMeta.copy(apiMappings = projectMeta.apiMappings ++ apiMappingsFinal)

    "dealing with organizations" should {

      def route(label: String): Route = {
        import monix.execution.Scheduler.Implicits.global
        Routes.wrap(
          (get & org(label)) { o =>
            complete(StatusCodes.OK -> o)
          }
        )
      }

      "fetch the organization by label from admin client" in {

        client.fetchOrganization("organization") shouldReturn Task.pure(Option(orgMeta))

        Get("/") ~> route("organization") ~> check {
          responseAs[Organization] shouldEqual orgMeta
        }
      }

      "fetch the organization by UUID from admin client" in {

        client.fetchOrganization(orgMeta.uuid) shouldReturn Task.pure(Option(orgMeta))

        Get("/") ~> route(orgMeta.uuid.toString) ~> check {
          responseAs[Organization] shouldEqual orgMeta
        }
      }

      "fetch the organization by label when not found from UUID on admin client" in {

        client.fetchOrganization(orgMeta.uuid) shouldReturn Task.pure(None)
        client.fetchOrganization(orgMeta.uuid.toString) shouldReturn Task.pure(Option(orgMeta))

        Get("/") ~> route(orgMeta.uuid.toString) ~> check {
          responseAs[Organization] shouldEqual orgMeta
        }
      }

      "reject organization when not found on the admin client" in {
        client.fetchOrganization("organization") shouldReturn Task.pure(None)

        Get("/") ~> route("organization") ~> check {
          status shouldEqual StatusCodes.NotFound
          responseAs[Error].tpe shouldEqual classNameOf[OrganizationNotFound]
        }
      }
    }

    "dealing with project" should {

      def route: Route = {
        import monix.execution.Scheduler.Implicits.global
        Routes.wrap(
          (get & project) { project =>
            complete(StatusCodes.OK -> project)
          }
        )
      }

      def notDeprecatedRoute(implicit proj: Project): Route =
        Routes.wrap(
          (get & projectNotDeprecated) {
            complete(StatusCodes.OK)
          }
        )

      val orgRef     = OrganizationRef(projectMeta.organizationUuid)
      val projectRef = ProjectRef(projectMeta.uuid)

      "fetch the project by label from the cache" in {

        projectCache.get(label) shouldReturn Task.pure(Option(projectMeta))

        Get("/organization/project") ~> route ~> check {
          responseAs[Project] shouldEqual projectMetaResp
        }
      }

      "fetch the project by UUID from the cache" in {

        projectCache.get(orgRef, projectRef) shouldReturn Task.pure(Option(projectMeta))
        projectCache.get(ProjectLabel(orgRef.show, projectRef.show)) shouldReturn Task.pure(None)

        Get(s"/${orgRef.show}/${projectRef.show}") ~> route ~> check {
          responseAs[Project] shouldEqual projectMetaResp
          projectCache.get(orgRef, projectRef) wasCalled once
        }
      }

      "fetch the project by label from admin client when not present on the cache" in {
        projectCache.get(label) shouldReturn Task.pure(None)
        client.fetchProject("organization", "project") shouldReturn Task.pure(Option(projectMeta))
        initializer(projectMeta, subject) shouldReturn Task.unit

        Get("/organization/project") ~> route ~> check {
          responseAs[Project] shouldEqual projectMetaResp
        }
      }

      "fetch the project by UUID from admin client when not present on the cache" in {
        projectCache.get(orgRef, projectRef) shouldReturn Task.pure(None)
        projectCache.get(ProjectLabel(orgRef.show, projectRef.show)) shouldReturn Task.pure(None)
        client.fetchProject(orgRef.id, projectRef.id) shouldReturn Task.pure(Option(projectMeta))
        initializer(projectMeta, subject) shouldReturn Task.unit

        Get(s"/${orgRef.show}/${projectRef.show}") ~> route ~> check {
          responseAs[Project] shouldEqual projectMetaResp
        }
      }

      "fetch the project by label when UUID not found from admin client nor from the cache" in {
        projectCache.get(orgRef, projectRef) shouldReturn Task.pure(None)
        client.fetchProject(orgRef.id, projectRef.id) shouldReturn Task.pure(None)
        projectCache.get(ProjectLabel(orgRef.show, projectRef.show)) shouldReturn Task.pure(Option(projectMeta))
        initializer(projectMeta, subject) shouldReturn Task.unit

        Get(s"/${orgRef.show}/${projectRef.show}") ~> route ~> check {
          responseAs[Project] shouldEqual projectMetaResp
        }
      }

      "fetch the project by label from admin client when cache throws an error" in {
        projectCache.get(label) shouldReturn Task.raiseError(new RuntimeException)
        client.fetchProject("organization", "project") shouldReturn Task.pure(Option(projectMeta))
        initializer(projectMeta, subject) shouldReturn Task.unit

        Get("/organization/project") ~> route ~> check {
          responseAs[Project] shouldEqual projectMetaResp
        }
      }

      "reject project by label when not found neither in the cache nor calling the admin client" in {
        projectCache.get(label) shouldReturn Task.pure(None)
        client.fetchProject("organization", "project") shouldReturn Task.pure(None)

        Get("/organization/project") ~> route ~> check {
          status shouldEqual StatusCodes.NotFound
          responseAs[Error].tpe shouldEqual classNameOf[ProjectNotFound]
        }
      }

      "reject when admin client signals forbidden" in {
        val label = ProjectLabel("organization", "project")
        projectCache.get(label) shouldReturn Task.pure(None)
        client.fetchProject("organization", "project") shouldReturn Task.raiseError(IamClientError.Forbidden(""))

        Get("/organization/project") ~> route ~> check {
          status shouldEqual StatusCodes.Forbidden
          responseAs[Error].tpe shouldEqual "AuthorizationFailed"
        }
      }

      "reject when admin client signals another error" in {
        val label = ProjectLabel("organization", "project")
        projectCache.get(label) shouldReturn Task.pure(None)
        client.fetchProject("organization", "project") shouldReturn
          Task.raiseError(IamClientError.UnknownError(StatusCodes.InternalServerError, ""))

        Get("/organization/project") ~> route ~> check {
          status shouldEqual StatusCodes.InternalServerError
          responseAs[Error].tpe shouldEqual classNameOf[KgError.InternalError]
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
