package ch.epfl.bluebrain.nexus.delta

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Accept, OAuth2BearerToken}
import akka.http.scaladsl.server.{AuthorizationFailedRejection, MalformedQueryParamRejection}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.{AclsRoutes, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Organization, Project}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, IdentitiesDummy, PermissionsDummy, RemoteContextResolutionDummy}
import ch.epfl.bluebrain.nexus.delta.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import scala.collection.immutable.SortedMap

class AclsRoutesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with CirceLiteral
    with CirceEq
    with DeltaDirectives
    with IOFixedClock
    with IOValues
    with OptionValues
    with RouteHelpers
    with TestMatchers
    with Inspectors
    with TestHelpers {

  val user      = User("uuid", Label.unsafe("realm"))
  val user2     = User("uuid2", Label.unsafe("realm"))
  val group     = Group("mygroup", Label.unsafe("myrealm"))
  val group2    = Group("mygroup2", Label.unsafe("myrealm"))
  val readWrite =
    Set(Permission.unsafe("acls/read"), Permission.unsafe("acls/write"), Permission.unsafe("events/read"))
  val manage    = Set(Permission.unsafe("acls/manage"))

  val userAcl   = Acl(user -> readWrite)
  val groupAcl  = Acl(group -> manage)
  val group2Acl = Acl(group2 -> manage)
  val token     = OAuth2BearerToken("valid")
  val selfAcls  = userAcl ++ groupAcl
  val allAcls   = userAcl ++ groupAcl ++ group2Acl

  implicit val caller: Caller   = Caller(user, Set(user, group))
  implicit val subject: Subject = caller.subject

  implicit private val baseUri: BaseUri                  =
    BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val ordering: JsonKeyOrdering         = JsonKeyOrdering.alphabetical
  implicit private val s: Scheduler                      = Scheduler.global
  implicit private val rcr: RemoteContextResolutionDummy =
    RemoteContextResolutionDummy(
      contexts.resource -> jsonContentOf("contexts/resource.json"),
      contexts.error    -> jsonContentOf("contexts/error.json")
    )
  private val acls                                       = AclsDummy(
    PermissionsDummy(
      Set(
        Permission.unsafe("acls/read"),
        Permission.unsafe("acls/write"),
        Permission.unsafe("acls/manage"),
        Permission.unsafe("events/read")
      )
    )
  ).accepted

  def aclEntryJson(identity: Identity, permissions: Set[Permission]): Json =
    Json.obj(
      "identity"    -> identity.asJson,
      "permissions" -> Json.fromValues(permissions.toSeq.map(_.toString).sorted.map(Json.fromString))
    )

  def aclJson(acl: Acl): Json = Json.obj(
    "acl" -> Json.fromValues(acl.value.map { case (id, p) =>
      aclEntryJson(id, p)
    })
  )

  def expectedUpdateResponse(rev: Long, createdBy: Subject, updatedBy: Subject, path: String): Json =
    jsonContentOf(
      "/acls/write-response-routes.json",
      Map(
        "path"      -> path.stripSuffix("/"),
        "createdBy" -> createdBy.id.toString,
        "updatedBy" -> updatedBy.id.toString
      )
    ) deepMerge Json.obj("_rev" -> Json.fromLong(rev))

  def expectedResponse(rev: Long, total: Long, acls: Map[String, Acl]): Json = {
    jsonContentOf("/acls/acls-route-response.json", Map("total" -> total)) deepMerge Json
      .obj(
        "_results" -> Json.fromValues(
          acls.map { case (path, acl) =>
            aclJson(acl) deepMerge Json.obj(
              "_path"          -> Json.fromString(path),
              "@id"            -> Json.fromString(s"http://localhost/v1/acls${path.stripSuffix("/")}"),
              "@type"          -> Json.fromString("AccessControlList"),
              "_constrainedBy" -> Json.fromString("https://bluebrain.github.io/nexus/schemas/acls.json"),
              "_deprecated"    -> Json.fromBoolean(false),
              "_createdAt"     -> Json.fromString("1970-01-01T00:00:00Z"),
              "_updatedAt"     -> Json.fromString("1970-01-01T00:00:00Z"),
              "_createdBy"     -> Json.fromString(user.id.toString),
              "_updatedBy"     -> Json.fromString(user.id.toString),
              "_rev"           -> Json.fromLong(rev)
            )
          }
        )
      )
  }

  private val identities = IdentitiesDummy(Map(AuthToken(token.token) -> caller))

  val routes = AclsRoutes(identities, acls).routes

  val paths = Seq("/", "/myorg", "/myorg/myproj")

  "ACL routes" should {

    "fail to create acls without permissions" in {
      forAll(paths) { path =>
        Put(s"/v1/acls$path", aclJson(userAcl).toEntity) ~> addCredentials(token) ~> routes ~> check {
          rejection shouldEqual AuthorizationFailedRejection
        }
      }

    }

    "create ACL" in {
      acls.replace(AclAddress.Root, userAcl, 0L).accepted
      forAll(paths.drop(1)) { path =>
        Put(s"/v1/acls$path", aclJson(userAcl).toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedUpdateResponse(1L, user, user, path)
          status shouldEqual StatusCodes.Created
        }
      }

    }

    "append ACL" in {
      val patch = aclJson(groupAcl) deepMerge Json.obj("@type" -> Json.fromString("Append"))
      forAll(paths) { path =>
        Patch(s"/v1/acls$path?rev=1", patch.toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedUpdateResponse(2L, user, user, path)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get the events stream" in {
      Get("/v1/acls/events") ~> addCredentials(token) ~> Accept(`*/*`) ~> routes ~> check {
        mediaType shouldBe `text/event-stream`
        response.asString.strip shouldEqual contentOf("/acls/eventstream.txt").strip
      }
    }

    "append non self acls" in {
      val patch = aclJson(group2Acl) deepMerge Json.obj("@type" -> Json.fromString("Append"))
      forAll(paths) { path =>
        Patch(s"/v1/acls$path?rev=2", patch.toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedUpdateResponse(3L, user, user, path)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true" in {
      forAll(paths) { path =>
        Get(s"/v1/acls$path") ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(3L, 1L, Map(path -> selfAcls))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = false" in {
      forAll(paths) { path =>
        Get(s"/v1/acls$path?self=false") ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(3L, 1L, Map(path -> allAcls))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true and rev = 1" in {
      forAll(paths) { path =>
        Get(s"/v1/acls$path?rev=1") ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(1L, 1L, Map(path -> (userAcl)))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true with org path containing *" in {
      acls.append(Organization(Label.unsafe("myorg2")), userAcl, 0L).accepted
      acls.append(Organization(Label.unsafe("myorg2")), groupAcl, 1L).accepted
      acls.append(Organization(Label.unsafe("myorg2")), group2Acl, 2L).accepted
      Get(s"/v1/acls/*") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          2L,
          Map("/myorg" -> selfAcls, "/myorg2" -> selfAcls)
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org path containing *" in {
      Get(s"/v1/acls/*?self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          2L,
          Map("/myorg" -> allAcls, "/myorg2" -> allAcls)
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with project path containing *" in {
      acls
        .append(Project(Label.unsafe("myorg"), Label.unsafe("myproj2")), userAcl, 0L)
        .accepted
      acls
        .append(Project(Label.unsafe("myorg"), Label.unsafe("myproj2")), groupAcl, 1L)
        .accepted
      acls
        .append(Project(Label.unsafe("myorg"), Label.unsafe("myproj2")), group2Acl, 2L)
        .accepted
      Get(s"/v1/acls/myorg/*") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          2L,
          Map("/myorg/myproj" -> selfAcls, "/myorg/myproj2" -> selfAcls)
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with project path containing *" in {
      Get(s"/v1/acls/myorg/*?self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          2L,
          Map(
            "/myorg/myproj"  -> allAcls,
            "/myorg/myproj2" -> allAcls
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org and project path containing *" in {
      acls
        .append(Project(Label.unsafe("myorg2"), Label.unsafe("myproj2")), userAcl, 0L)
        .accepted
      acls
        .append(Project(Label.unsafe("myorg2"), Label.unsafe("myproj2")), groupAcl, 1L)
        .accepted
      acls
        .append(Project(Label.unsafe("myorg2"), Label.unsafe("myproj2")), group2Acl, 2L)
        .accepted
      Get(s"/v1/acls/*/*") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          3L,
          Map(
            "/myorg/myproj"   -> selfAcls,
            "/myorg/myproj2"  -> selfAcls,
            "/myorg2/myproj2" -> selfAcls
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org and project path containing *" in {
      Get(s"/v1/acls/*/*?self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          3L,
          Map(
            "/myorg/myproj"   -> allAcls,
            "/myorg/myproj2"  -> allAcls,
            "/myorg2/myproj2" -> allAcls
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with project path containing * with ancestors" in {
      Get(s"/v1/acls/myorg/*?ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          4L,
          Map(
            "/"              -> selfAcls,
            "/myorg"         -> selfAcls,
            "/myorg/myproj"  -> selfAcls,
            "/myorg/myproj2" -> selfAcls
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with project path containing * with ancestors" in {
      Get(s"/v1/acls/myorg/*?ancestors=true&self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          4L,
          Map(
            "/"              -> allAcls,
            "/myorg"         -> allAcls,
            "/myorg/myproj"  -> allAcls,
            "/myorg/myproj2" -> allAcls
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org path containing * with ancestors" in {
      Get(s"/v1/acls/*?ancestors=true") ~> addCredentials(token) ~> routes ~> check {

        response.asJson shouldEqual expectedResponse(
          3L,
          3L,
          Map("/" -> selfAcls, "/myorg" -> selfAcls, "/myorg2" -> selfAcls)
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org path containing * with ancestors" in {
      Get(s"/v1/acls/*?ancestors=true&self=false") ~> addCredentials(token) ~> routes ~> check {

        response.asJson shouldEqual expectedResponse(
          3L,
          3L,
          Map(
            "/"       -> allAcls,
            "/myorg"  -> allAcls,
            "/myorg2" -> allAcls
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org  and project path containing * with ancestors" in {
      Get(s"/v1/acls/*/*?ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          6L,
          SortedMap(
            "/"               -> selfAcls,
            "/myorg"          -> selfAcls,
            "/myorg2"         -> selfAcls,
            "/myorg/myproj"   -> selfAcls,
            "/myorg/myproj2"  -> selfAcls,
            "/myorg2/myproj2" -> selfAcls
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org  and project path containing * with ancestors" in {
      Get(s"/v1/acls/*/*?ancestors=true&self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          6L,
          SortedMap(
            "/"               -> allAcls,
            "/myorg"          -> allAcls,
            "/myorg2"         -> allAcls,
            "/myorg/myproj"   -> allAcls,
            "/myorg/myproj2"  -> allAcls,
            "/myorg2/myproj2" -> allAcls
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false and rev = 2 when response is an empty ACL" in {
      Get(s"/v1/acls/myorg/myproj1?rev=2&self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(2L, 0L, Map.empty)
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true and ancestors = true" in {
      Get(s"/v1/acls/myorg/myproj?ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          3L,
          Map("/" -> selfAcls, "/myorg" -> selfAcls, "/myorg/myproj" -> selfAcls)
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "subtract ACL" in {
      val patch = aclJson(userAcl) deepMerge Json.obj("@type" -> Json.fromString("Subtract"))
      forAll(paths) { path =>
        Patch(s"/v1/acls$path?rev=3", patch.toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedUpdateResponse(4L, user, user, path)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "delete ACL" in {
      forAll(paths) { path =>
        Delete(s"/v1/acls$path?rev=4") ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedUpdateResponse(5L, user, user, path)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "return error when getting ACL with rev and ancestors = true" in {
      Get(s"/v1/acls/myorg/myproj?rev=2&ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        rejection shouldEqual MalformedQueryParamRejection(
          "rev",
          "rev and ancestors query parameters cannot be present simultaneously"
        )
      }
    }

  }
}
