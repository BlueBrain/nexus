package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`text/event-stream`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Accept, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{acls => aclsPermissions, _}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.{Organization, Project, Root}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, IdentitiesDummy, PermissionsDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import io.circe.syntax.EncoderOps
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class AclsRoutesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with RouteHelpers
    with TestMatchers
    with Inspectors
    with TestHelpers
    with RouteFixtures {

  val user      = User("uuid", Label.unsafe("realm"))
  val user2     = User("uuid2", Label.unsafe("realm"))
  val group     = Group("mygroup", Label.unsafe("myrealm"))
  val group2    = Group("mygroup2", Label.unsafe("myrealm"))
  val readWrite =
    Set(aclsPermissions.read, aclsPermissions.write, events.read)

  val managePermission = Permission.unsafe("acls/manage")
  val manage           = Set(managePermission)

  def userAcl(address: AclAddress)     = Acl(address, user -> readWrite)
  def userAclRead(address: AclAddress) = Acl(address, user -> Set(aclsPermissions.read))
  def groupAcl(address: AclAddress)    = Acl(address, group -> manage)
  def group2Acl(address: AclAddress)   = Acl(address, group2 -> manage)
  val token                            = OAuth2BearerToken("valid")
  def selfAcls(address: AclAddress)    = userAcl(address) ++ groupAcl(address)
  def allAcls(address: AclAddress)     = userAcl(address) ++ groupAcl(address) ++ group2Acl(address)

  implicit val caller: Caller   = Caller(user, Set(user, group))
  implicit val subject: Subject = caller.subject

  val myOrg         = Organization(Label.unsafe("myorg"))
  val myOrg2        = Organization(Label.unsafe("myorg2"))
  val myOrgMyProj   = Project(Label.unsafe("myorg"), Label.unsafe("myproj"))
  val myOrgMyProj2  = Project(Label.unsafe("myorg"), Label.unsafe("myproj2"))
  val myOrg2MyProj2 = Project(Label.unsafe("myorg2"), Label.unsafe("myproj2"))

  private val acls = AclsDummy(
    PermissionsDummy(Set(aclsPermissions.read, aclsPermissions.write, managePermission, events.read))
  ).accepted

  def aclEntryJson(identity: Identity, permissions: Set[Permission]): Json =
    Json.obj(
      "identity"    -> identity.asJson,
      "permissions" -> Json.fromValues(permissions.toSeq.map(_.toString).sorted.map(Json.fromString))
    )

  def aclAddressJson(address: AclAddress): Json =
    address match {
      case AclAddress.Root       => Json.fromString("/")
      case Organization(org)     => Json.fromString(s"/${org.value}")
      case Project(org, project) => Json.fromString(s"/${org.value}/${project.value}")
    }

  def aclJson(acl: Acl): Json =
    Json.obj(
      "_path" -> aclAddressJson(acl.address),
      "acl"   -> Json.fromValues(acl.value.map { case (id, p) => aclEntryJson(id, p) })
    )

  def expectedResponse(rev: Long, total: Long, acls: Seq[Acl]): Json = {
    val results = acls.map { acl =>
      val meta = aclMetadata(acl.address, rev, createdBy = user, updatedBy = user).removeKeys(keywords.context)
      aclJson(acl) deepMerge meta
    }
    jsonContentOf("/acls/acls-route-response.json", "total" -> total) deepMerge
      Json.obj("_results"                                   -> Json.fromValues(results))
  }

  private val identities = IdentitiesDummy(Map(AuthToken(token.token) -> caller))

  val routes = Route.seal(AclsRoutes(identities, acls).routes)

  val paths = Seq(
    "/"             -> AclAddress.Root,
    "/myorg"        -> AclAddress.Organization(Label.unsafe("myorg")),
    "/myorg/myproj" -> AclAddress.Project(Label.unsafe("myorg"), Label.unsafe("myproj"))
  )

  "ACL routes" should {

    "fail to create acls without permissions" in {
      forAll(paths) { case (path, address) =>
        val json = aclJson(userAcl(address)).removeKeys("_path")
        Put(s"/v1/acls$path", json.toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }

    }

    "create ACL" in {
      acls.replace(userAcl(AclAddress.Root), 0L).accepted
      val replace = aclJson(userAcl(AclAddress.Root)).removeKeys("_path")
      forAll(paths.drop(1)) { case (path, address) =>
        Put(s"/v1/acls$path", replace.toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.Created
        }
      }

    }

    "append ACL" in {
      val patch = aclJson(groupAcl(Root)).removeKeys("_path") deepMerge Json.obj("@type" -> Json.fromString("Append"))
      forAll(paths) { case (path, address) =>
        Patch(s"/v1/acls$path?rev=1", patch.toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, rev = 2L, createdBy = user, updatedBy = user)
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
      val patch = aclJson(group2Acl(Root)).removeKeys("_path") deepMerge
        Json.obj("@type" -> Json.fromString("Append"))
      forAll(paths) { case (path, address) =>
        Patch(s"/v1/acls$path?rev=2", patch.toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, rev = 3L, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true" in {
      forAll(paths) { case (path, address) =>
        Get(s"/v1/acls$path") ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(3L, 1L, Seq(selfAcls(address)))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = false" in {
      forAll(paths) { case (path, address) =>
        Get(s"/v1/acls$path?self=false") ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(3L, 1L, Seq(allAcls(address)))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true and rev = 1" in {
      forAll(paths) { case (path, address) =>
        Get(s"/v1/acls$path?rev=1") ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(1L, 1L, Seq(userAcl(address)))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true with org path containing *" in {

      acls.append(userAcl(myOrg2), 0L).accepted
      acls.append(groupAcl(myOrg2), 1L).accepted
      acls.append(group2Acl(myOrg2), 2L).accepted
      Get(s"/v1/acls/*") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(3L, 2L, Seq(selfAcls(myOrg), selfAcls(myOrg2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org path containing *" in {
      Get(s"/v1/acls/*?self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(3L, 2L, Seq(allAcls(myOrg), allAcls(myOrg2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with project path containing *" in {
      acls.append(userAcl(myOrgMyProj2), 0L).accepted
      acls.append(groupAcl(myOrgMyProj2), 1L).accepted
      acls.append(group2Acl(myOrgMyProj2), 2L).accepted
      Get(s"/v1/acls/myorg/*") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(3L, 2L, Seq(selfAcls(myOrgMyProj), selfAcls(myOrgMyProj2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with project path containing *" in {
      Get(s"/v1/acls/myorg/*?self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(3L, 2L, Seq(allAcls(myOrgMyProj), allAcls(myOrgMyProj2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org and project path containing *" in {
      acls.append(userAcl(myOrg2MyProj2), 0L).accepted
      acls.append(groupAcl(myOrg2MyProj2), 1L).accepted
      acls.append(group2Acl(myOrg2MyProj2), 2L).accepted
      Get(s"/v1/acls/*/*") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(3L, 3L, Seq(selfAcls(myOrgMyProj), selfAcls(myOrgMyProj2), selfAcls(myOrg2MyProj2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org and project path containing *" in {
      Get(s"/v1/acls/*/*?self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(3L, 3L, Seq(allAcls(myOrgMyProj), allAcls(myOrgMyProj2), allAcls(myOrg2MyProj2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with project path containing * with ancestors" in {
      Get(s"/v1/acls/myorg/*?ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(3L, 4L, Seq(selfAcls(Root), selfAcls(myOrg), selfAcls(myOrgMyProj), selfAcls(myOrgMyProj2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with project path containing * with ancestors" in {
      Get(s"/v1/acls/myorg/*?ancestors=true&self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(3L, 4L, Seq(allAcls(Root), allAcls(myOrg), allAcls(myOrgMyProj), allAcls(myOrgMyProj2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org path containing * with ancestors" in {
      Get(s"/v1/acls/*?ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(3L, 3L, Seq(selfAcls(Root), selfAcls(myOrg), selfAcls(myOrg2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org path containing * with ancestors" in {
      Get(s"/v1/acls/*?ancestors=true&self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(3L, 3L, Seq(allAcls(Root), allAcls(myOrg), allAcls(myOrg2)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org  and project path containing * with ancestors" in {
      Get(s"/v1/acls/*/*?ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          6L,
          Seq(
            selfAcls(Root),
            selfAcls(myOrg),
            selfAcls(myOrgMyProj),
            selfAcls(myOrgMyProj2),
            selfAcls(myOrg2),
            selfAcls(myOrg2MyProj2)
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
          Seq(
            allAcls(Root),
            allAcls(myOrg),
            allAcls(myOrgMyProj),
            allAcls(myOrgMyProj2),
            allAcls(myOrg2),
            allAcls(myOrg2MyProj2)
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false and rev = 2 when response is an empty ACL" in {
      Get(s"/v1/acls/myorg/myproj1?rev=2&self=false") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(2L, 0L, Seq.empty)
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true and ancestors = true" in {
      Get(s"/v1/acls/myorg/myproj?ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(3L, 3L, Seq(selfAcls(Root), selfAcls(myOrg), selfAcls(myOrgMyProj)))
        status shouldEqual StatusCodes.OK
      }
    }

    "subtract ACL" in {
      val patch = aclJson(userAclRead(Root)).removeKeys("_path") deepMerge
        Json.obj("@type" -> Json.fromString("Subtract"))
      forAll(paths) { case (path, address) =>
        Patch(s"/v1/acls$path?rev=3", patch.toEntity) ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, rev = 4L, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "delete ACL" in {
      forAll(paths) { case (path, address) =>
        Delete(s"/v1/acls$path?rev=4") ~> addCredentials(token) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, rev = 5L, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "return error when getting ACL with rev and ancestors = true" in {
      Get(s"/v1/acls/myorg/myproj?rev=2&ancestors=true") ~> addCredentials(token) ~> routes ~> check {
        response.asJson shouldEqual jsonContentOf("errors/acls-malformed-query-params.json")
        status shouldEqual StatusCodes.BadRequest
      }
    }

  }
}
