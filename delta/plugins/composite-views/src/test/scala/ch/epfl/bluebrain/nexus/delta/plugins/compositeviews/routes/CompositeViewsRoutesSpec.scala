package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, CompositeViewsFixture, CompositeViewsSetup}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectCountsCollection, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{ProgressesStatistics, ProjectsCounts}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.{CompositeViewProjectionId, SourceProjectionId}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.instances._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.syntax._
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure, Inspectors, OptionValues}
import slick.jdbc.JdbcBackend

import java.time.Instant

class CompositeViewsRoutesSpec
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
    with CompositeViewsSetup
    with BeforeAndAfterAll
    with TestHelpers
    with CompositeViewsFixture {

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem = system.toTyped

  implicit val ordering: JsonKeyOrdering          =
    JsonKeyOrdering.default(topKeys = List("@context", "@id", "@type", "reason", "details", "_total", "_results"))
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val realm                     = Label.unsafe("myrealm")
  val bob                       = User("Bob", realm)
  implicit val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val identities        = IdentitiesDummy(Map(AuthToken("bob") -> caller))
  private val asBob             = addCredentials(OAuth2BearerToken("bob"))

  val undefinedPermission = Permission.unsafe("not/defined")
  val allowedPerms        = Set(
    permissions.read,
    permissions.write,
    events.read
  )

  private val perms  = PermissionsDummy(allowedPerms).accepted
  private val realms = RealmSetup.init(realm).accepted
  private val acls   = AclsDummy(perms, realms).accepted

  val org  = Label.unsafe("myorg")
  val base = nxv.base

  def projectSetup =
    ProjectSetup
      .init(
        orgsToCreate = org :: Nil,
        projectsToCreate = project :: Nil
      )

  private val now = Instant.now()

  private val projectStats = ProjectCount(10, now)

  private val projectsCounts = new ProjectsCounts {
    override def get(): UIO[ProjectCountsCollection]                 =
      UIO(ProjectCountsCollection(Map(projectRef -> projectStats)))
    override def get(project: ProjectRef): UIO[Option[ProjectCount]] = get().map(_.get(project))
  }

  private val esProjectionId       =
    CompositeViewProjectionId(SourceProjectionId(s"${uuid}_4"), ElasticSearchViews.projectionId(uuid, 4))
  private val blazeProjectionId    =
    CompositeViewProjectionId(SourceProjectionId(s"${uuid}_4"), BlazegraphViews.projectionId(uuid, 4))
  private val viewsProgressesCache = KeyValueStore.localLRU[ProjectionId, ProjectionProgress[Unit]](10L).accepted
  viewsProgressesCache.put(esProjectionId, ProjectionProgress(Sequence(3), now, 3, 1, 0, 1)).accepted
  viewsProgressesCache.put(blazeProjectionId, ProjectionProgress(Sequence(1), now, 1, 0, 0, 0)).accepted

  private val statisticsProgress = new ProgressesStatistics(viewsProgressesCache, projectsCounts)

  var restartedView: Option[(ProjectRef, Iri)]                                                          = None
  private def restart(id: Iri, projectRef: ProjectRef)                                                  = UIO { restartedView = Some(projectRef -> id) }.void
  var restartedProjection: Option[(ProjectRef, Iri, Set[CompositeViewProjectionId])]                    = None
  private def restartProjection(id: Iri, projectRef: ProjectRef, projs: Set[CompositeViewProjectionId]) = UIO {
    restartedProjection = Some((projectRef, id, projs))
  }.void
  private val (orgs, projects)                                                                          = projectSetup.accepted
  private val views: CompositeViews                                                                     = initViews(orgs, projects).accepted
  private val routes                                                                                    =
    Route.seal(CompositeViewsRoutes(identities, acls, projects, views, restart, restartProjection, statisticsProgress))

  val viewSource        = jsonContentOf("composite-view-source.json")
  val viewSourceUpdated = jsonContentOf("composite-view-source-updated.json")

  "Composite views routes" should {
    "fail to create a view without permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0L).accepted
      Post("/v1/views/myorg/myproj", viewSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }

    "create a view" in {
      acls
        .append(Acl(AclAddress.Root, caller.subject -> Set(permissions.write, permissions.read)), 1L)
        .accepted
      Post("/v1/views/myorg/myproj", viewSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 1,
          "deprecated" -> false
        )
      }
    }

    "reject creation of a view which already exists" in {
      Put(s"/v1/views/myorg/myproj/$uuid", viewSource.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/view-already-exists.json", "uuid" -> uuid)
      }
    }

    "fail to update a view without permission" in {
      Put(s"/v1/views/myorg/myproj/$uuid?rev=1", viewSource.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }

    "update a view" in {
      Put(s"/v1/views/myorg/myproj/$uuid?rev=1", viewSourceUpdated.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 2,
          "deprecated" -> false
        )
      }
    }

    "reject update of a view at a non-existent revision" in {
      Put(s"/v1/views/myorg/myproj/$uuid?rev=3", viewSourceUpdated.toEntity) ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("routes/errors/incorrect-rev.json", "provided" -> 3, "expected" -> 2)
      }
    }

    "tag a view" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post(s"/v1/views/myorg/myproj/$uuid/tags?rev=2", payload.toEntity) ~> asBob ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 3,
          "deprecated" -> false
        )
      }
    }

    "fail to deprecate a view without permission" in {
      Delete(s"/v1/views/myorg/myproj/$uuid?rev=3") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }

    "reject a deprecation of a view without rev" in {
      Delete(s"/v1/views/myorg/myproj/$uuid") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "deprecate a view" in {
      Delete(s"/v1/views/myorg/myproj/$uuid?rev=3") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view-metadata.json",
          "uuid"       -> uuid,
          "rev"        -> 4,
          "deprecated" -> true
        )
      }
    }

    "fail to fetch a view without permission" in {
      Get(s"/v1/views/myorg/myproj/$uuid") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
      }
    }

    "fetch a view" in {
      Get(s"/v1/views/myorg/myproj/$uuid") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "routes/responses/view.json",
          "uuid"            -> uuid,
          "deprecated"      -> true,
          "rev"             -> 4,
          "rebuildInterval" -> "2 minutes"
        )
      }
    }

    "fetch a view by rev or tag" in {
      val endpoints = List(
        s"/v1/views/myorg/myproj/$uuid?tag=mytag",
        s"/v1/resources/myorg/myproj/_/$uuid?tag=mytag",
        s"/v1/views/myorg/myproj/$uuid?rev=1",
        s"/v1/resources/myorg/myproj/_/$uuid?rev=1"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf(
            "routes/responses/view.json",
            "uuid"            -> uuid,
            "deprecated"      -> false,
            "rev"             -> 1,
            "rebuildInterval" -> "1 minute"
          ).mapObject(_.remove("resourceTag"))
        }
      }
    }

    "fetch a view source" in {
      val endpoints = List(s"/v1/views/myorg/myproj/$uuid/source", s"/v1/resources/myorg/myproj/_/$uuid/source")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual viewSourceUpdated.removeAllKeys("token")
        }
      }
    }

    "fetch the view tags" in {
      val endpoints = List(s"/v1/views/myorg/myproj/$uuid/tags", s"/v1/resources/myorg/myproj/_/$uuid/tags")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(
              Vocabulary.contexts.tags
            )
        }
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get(s"/v1/views/myorg/myproj/$uuid?rev=1&tag=mytag") ~> asBob ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("routes/errors/tag-and-rev-error.json")
      }
    }

    "fail to fetch/delete offset without permission" in {
      val encodedId = UrlUtils.encode("http://example.com/blazegraph-projection")
      val endpoints = List(
        s"/v1/views/myorg/myproj/$uuid/offset",
        s"/v1/views/myorg/myproj/$uuid/projections/_/offset",
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/offset"
      )
      forAll(endpoints) { endpoint =>
        forAll(List(Get(endpoint), Delete(endpoint))) { req =>
          req ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("routes/errors/authorization-failed.json")
          }
        }
      }
    }

    "fetch offsets" in {
      val encodedId         = UrlUtils.encode("http://example.com/blazegraph-projection")
      val viewOffsets       = jsonContentOf("routes/responses/view-offsets.json")
      val projectionOffsets = jsonContentOf("routes/responses/view-offsets-projection.json")
      val endpoints         = List(
        s"/v1/views/myorg/myproj/$uuid/offset"                        -> viewOffsets,
        s"/v1/views/myorg/myproj/$uuid/projections/_/offset"          -> viewOffsets,
        s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/offset" -> projectionOffsets
      )
      forAll(endpoints) { case (endpoint, expected) =>
        Get(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expected
        }
      }
    }

    "delete offsets" in {
      val encodedId         = UrlUtils.encode("http://example.com/blazegraph-projection")
      val viewOffsets       =
        jsonContentOf("routes/responses/view-offsets.json").replaceKeyWithValue("offset", NoOffset.asJson)
      val projectionOffsets =
        jsonContentOf("routes/responses/view-offsets-projection.json").replaceKeyWithValue("offset", NoOffset.asJson)

      restartedView shouldEqual None
      Delete(s"/v1/views/myorg/myproj/$uuid/offset") ~> asBob ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asJson shouldEqual viewOffsets
        restartedView.value shouldEqual ((project.ref, nxv + uuid.toString))
      }

      restartedProjection shouldEqual None
      val endpoints = List(
        (s"/v1/views/myorg/myproj/$uuid/projections/_/offset", viewOffsets, Set(esProjectionId, blazeProjectionId)),
        (s"/v1/views/myorg/myproj/$uuid/projections/$encodedId/offset", projectionOffsets, Set(blazeProjectionId))
      )
      forAll(endpoints) { case (endpoint, expectedResult, resetProjections) =>
        Delete(endpoint) ~> asBob ~> routes ~> check {
          response.status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expectedResult
          restartedProjection.value shouldEqual ((project.ref, nxv + uuid.toString, resetProjections))
        }
      }
    }

  }

  private var db: JdbcBackend.Database = null

  override protected def createActorSystem(): ActorSystem =
    ActorSystem("CompositeViewsRoutesSpec", AbstractDBSpec.config)

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
