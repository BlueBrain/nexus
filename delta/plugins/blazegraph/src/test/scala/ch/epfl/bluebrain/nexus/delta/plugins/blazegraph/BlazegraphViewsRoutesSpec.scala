package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure, Inspectors, OptionValues}
import slick.jdbc.JdbcBackend

import java.util.UUID

class BlazegraphViewsRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with CancelAfterFailure
    with ConfigFixtures
    with BeforeAndAfterAll
    with TestHelpers {

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem = system.toTyped

  val uuid                                  = UUID.randomUUID()
  implicit val uuidF: UUIDF                 = UUIDF.fixed(uuid)
  implicit val sc: Scheduler                = Scheduler.global
  val realm                                 = Label.unsafe("myrealm")
  val bob                                   = User("Bob", realm)
  implicit val caller: Caller               = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> jsonContentOf("/contexts/metadata.json"),
    Vocabulary.contexts.error    -> jsonContentOf("contexts/error.json"),
    Vocabulary.contexts.tags     -> jsonContentOf("contexts/tags.json"),
    contexts.blazegraph          -> jsonContentOf("/contexts/blazegraph.json")
  )
  private val identities                    = IdentitiesDummy(Map(AuthToken("bob") -> caller))
  private val asBob                         = addCredentials(OAuth2BearerToken("bob"))

  val indexingSource  = jsonContentOf("indexing-view-source.json")
  val aggregateSource = jsonContentOf("aggregate-view-source.json")

  val updatedIndexingSource = indexingSource.mapObject(_.add("resourceTag", Json.fromString("v1.5")))

  val indexingViewId = nxv + "indexing-view"

  val undefinedPermission = Permission.unsafe("not/defined")

  val allowedPerms = Set(
    defaultPermission,
    permissions.read,
    permissions.write,
    events.read
  )

  private val perms  = PermissionsDummy(allowedPerms).accepted
  private val realms = RealmSetup.init(realm).accepted
  private val acls   = AclsDummy(perms, realms).accepted

  val org                      = Label.unsafe("org")
  val orgDeprecated            = Label.unsafe("org-deprecated")
  val base                     = nxv.base
  val project                  = ProjectGen.project("org", "proj", base = base, mappings = ApiMappings.default)
  val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
  val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
  val projectRef               = project.ref
  def projectSetup             =
    ProjectSetup
      .init(
        orgsToCreate = org :: orgDeprecated :: Nil,
        projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
        projectsToDeprecate = deprecatedProject.ref :: Nil,
        organizationsToDeprecate = orgDeprecated :: Nil
      )

  val viewRef                                     = ViewRef(project.ref, indexingViewId)
  val config                                      = BlazegraphViewsConfig(
    aggregate,
    keyValueStore,
    pagination,
    cacheIndexing,
    externalIndexing,
    processor
  )
  implicit val ordering: JsonKeyOrdering          = JsonKeyOrdering.alphabetical
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val tag = TagLabel.unsafe("v1.5")

  val doesntExistId = nxv + "doesntexist"

  val routes = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[BlazegraphViewEvent]](EventLogUtils.toEnvelope).hideErrors
    (orgs, projects) <- projectSetup
    views            <- BlazegraphViews(config, eventLog, perms, orgs, projects)
    routes            = Route.seal(BlazegraphViewsRoutes(views, identities, acls, projects))
  } yield routes).accepted

  "Blazegraph view routes" should {
    "fail to create a view without permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0L).accepted
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }
    "create an indexing view" in {
      acls
        .append(Acl(AclAddress.Root, caller.subject -> Set(permissions.write, permissions.read)), 1L)
        .accepted
      Post("/v1/views/org/proj", indexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "rev"        -> 1,
          "deprecated" -> false
        )
      }
    }

    "create an aggregate view" in {
      Put("/v1/views/org/proj/aggregate-view", aggregateSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf("routes/responses/aggregate-view-metadata.json")
      }
    }

    "reject creation of a view which already exits" in {
      Put("/v1/views/org/proj/aggregate-view", aggregateSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/view-already-exists.json")
      }

    }
    "fail to update a view without permission" in {
      Put("/v1/views/org/proj/aggregate-view?rev=1", aggregateSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }
    "update a view" in {
      Put("/v1/views/org/proj/indexing-view?rev=1", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "rev"        -> 2,
          "deprecated" -> false
        )

      }
    }
    "reject update of a view at a non-existent revision" in {
      Put("/v1/views/org/proj/indexing-view?rev=3", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/incorrect-rev.json", "provided" -> 3, "expected" -> 2)

      }
    }

    "tag a view" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/views/org/proj/indexing-view/tags?rev=2", payload.toEntity) ~> asBob ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "rev"        -> 3,
          "deprecated" -> false
        )
      }
    }

    "fail to deprecate a view without permission" in {
      Delete("/v1/views/org/proj/indexing-view?rev=3", updatedIndexingSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }
    "reject a deprecation of a view without rev" in {
      Delete("/v1/views/org/proj/indexing-view", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }
    "deprecate a view" in {
      Delete("/v1/views/org/proj/indexing-view?rev=3", updatedIndexingSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view-metadata.json",
          "rev"        -> 4,
          "deprecated" -> true
        )

      }
    }

    "fail to fetch a view without permission" in {
      Get("/v1/views/org/proj/indexing-view") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")

      }
    }
    "fetch a view" in {
      Get("/v1/views/org/proj/indexing-view") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/indexing-view.json",
          "uuid"       -> uuid,
          "deprecated" -> true,
          "rev"        -> 4
        )
      }
    }
    "fetch a view by rev or tag" in {
      forAll(List(Get("/v1/views/org/proj/indexing-view?tag=mytag"), Get("/v1/views/org/proj/indexing-view?rev=1"))) {
        req =>
          req ~> asBob ~> routes ~> check {
            response.status shouldEqual StatusCodes.OK
            response.asJson shouldEqual jsonContentOf(
              "routes/responses/indexing-view.json",
              "uuid"       -> uuid,
              "deprecated" -> false,
              "rev"        -> 1
            ).mapObject(_.remove("resourceTag"))
          }
      }

    }
    "fetch a view source" in {
      Get("/v1/views/org/proj/indexing-view/source") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual updatedIndexingSource
      }
    }
    "fetch the view tags" in {
      Get("/v1/views/org/proj/indexing-view/tags") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(
          Vocabulary.contexts.tags
        )
      }
    }
    "reject if provided rev and tag simultaneously" in {
      Get("/v1/views/org/proj/indexing-view?rev=1&tag=mytag") ~> asBob ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/tag-and-rev-error.json")
      }
    }
  }

  private var db: JdbcBackend.Database = null

  override protected def createActorSystem(): ActorSystem =
    ActorSystem("BlazegraphViewsRoutesSpec", AbstractDBSpec.config)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    db = AbstractDBSpec.beforeAll
    ()
  }

  override protected def afterAll(): Unit = {
    AbstractDBSpec.afterAll(db)
    super.afterAll()
  }

}
