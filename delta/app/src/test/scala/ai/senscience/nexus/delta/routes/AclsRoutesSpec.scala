package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Organization, Project, Root}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress, FlattenedAclStore}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, Acls, AclsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{acls as aclsPermissions, *}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{EventLogConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import io.circe.Json
import io.circe.syntax.KeyOps

import scala.concurrent.duration.*

class AclsRoutesSpec extends BaseRouteSpec {

  val realm1 = Label.unsafe("realm")
  val realm2 = Label.unsafe("myrealm")

  val user      = User("uuid", realm1)
  val user2     = User("uuid2", realm1)
  val group     = Group("mygroup", realm2)
  val group2    = Group("mygroup2", realm2)
  val readWrite = Set(aclsPermissions.read, aclsPermissions.write, events.read)

  val managePermission = Permission.unsafe("acls/manage")
  val manage           = Set(managePermission)

  def userAcl(address: AclAddress)     = Acl(address, user -> readWrite)
  def userAclRead(address: AclAddress) = Acl(address, user -> Set(aclsPermissions.read))
  def groupAcl(address: AclAddress)    = Acl(address, group -> manage)
  def group2Acl(address: AclAddress)   = Acl(address, group2 -> manage)
  def selfAcls(address: AclAddress)    = userAcl(address) ++ groupAcl(address)
  def allAcls(address: AclAddress)     = userAcl(address) ++ groupAcl(address) ++ group2Acl(address)

  implicit val caller: Caller = Caller(user, Set(user, group))

  val myOrg         = Organization(Label.unsafe("myorg"))
  val myOrg2        = Organization(Label.unsafe("myorg2"))
  val myOrg3        = Organization(Label.unsafe("myorg3"))
  val myOrgMyProj   = Project(Label.unsafe("myorg"), Label.unsafe("myproj"))
  val myOrgMyProj2  = Project(Label.unsafe("myorg"), Label.unsafe("myproj2"))
  val myOrg2MyProj2 = Project(Label.unsafe("myorg2"), Label.unsafe("myproj2"))

  def aclEntryJson(identity: Identity, permissions: Set[Permission]): Json =
    Json.obj(
      "identity"    := identity,
      "permissions" := permissions.toSeq.map(_.toString).sorted
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

  def expectedResponse(total: Long, acls: Seq[(Acl, Int)]): Json = {
    val results = acls.map { case (acl, rev) =>
      val meta = aclMetadata(acl.address, rev, createdBy = user, updatedBy = user).removeKeys(keywords.context)
      aclJson(acl) deepMerge meta
    }
    jsonContentOf("acls/acls-route-response.json", "total" -> total) deepMerge
      Json.obj("_results" -> Json.fromValues(results))
  }

  private val identities = IdentitiesDummy(caller)

  private lazy val aclStore = new FlattenedAclStore(xas)

  private lazy val acls =
    AclsImpl(
      IO.pure(Set(aclsPermissions.read, aclsPermissions.write, managePermission, events.read)),
      Acls.findUnknownRealms(_, Set(realm1, realm2)),
      Set(aclsPermissions.read, aclsPermissions.write, managePermission, events.read),
      EventLogConfig(QueryConfig(5, RefreshStrategy.Stop), 100.millis),
      aclStore,
      xas,
      clock
    )
  lazy val routes       = Route.seal(AclsRoutes(identities, acls, AclCheck(aclStore)).routes)

  val paths = Seq(
    "/"             -> AclAddress.Root,
    "/myorg"        -> AclAddress.Organization(Label.unsafe("myorg")),
    "/myorg/myproj" -> AclAddress.Project(Label.unsafe("myorg"), Label.unsafe("myproj"))
  )

  "ACL routes" should {

    "fail to create acls without permissions" in {
      forAll(paths) { case (path, address) =>
        val json = aclJson(userAcl(address)).removeKeys("_path")
        Put(s"/v1/acls$path", json.toEntity) ~> as(user) ~> routes ~> check {
          response.shouldBeForbidden
        }
      }

    }

    "create ACL" in {
      acls.replace(userAcl(AclAddress.Root), 0).accepted
      val replace = aclJson(userAcl(AclAddress.Root)).removeKeys("_path")
      forAll(paths.drop(1)) { case (path, address) =>
        Put(s"/v1/acls$path", replace.toEntity) ~> as(user) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.Created
        }
      }
    }

    "append ACL" in {
      val patch = aclJson(groupAcl(Root)).removeKeys("_path") deepMerge Json.obj("@type" -> Json.fromString("Append"))
      forAll(paths) { case (path, address) =>
        Patch(s"/v1/acls$path?rev=1", patch.toEntity) ~> as(user) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, rev = 2, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "append non self acls" in {
      val patch = aclJson(group2Acl(Root)).removeKeys("_path") deepMerge
        Json.obj("@type" -> Json.fromString("Append"))
      forAll(paths) { case (path, address) =>
        Patch(s"/v1/acls$path?rev=2", patch.toEntity) ~> as(user) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, rev = 3, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true" in {
      forAll(paths) { case (path, address) =>
        Get(s"/v1/acls$path") ~> as(user) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(1L, Seq((selfAcls(address), 3)))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = false" in {
      forAll(paths) { case (path, address) =>
        Get(s"/v1/acls$path?self=false") ~> as(user) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(1L, Seq((allAcls(address), 3)))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true and rev = 1" in {
      forAll(paths) { case (path, address) =>
        Get(s"/v1/acls$path?rev=1") ~> as(user) ~> routes ~> check {
          response.asJson shouldEqual expectedResponse(1L, Seq((userAcl(address), 1)))
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "get ACL self = true with org path containing *" in {
      acls.append(userAcl(myOrg2), 0).accepted
      acls.append(groupAcl(myOrg2), 1).accepted
      acls.append(group2Acl(myOrg2), 2).accepted
      Get(s"/v1/acls/*") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(2L, Seq((selfAcls(myOrg), 3), (selfAcls(myOrg2), 3)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org path containing *" in {
      Get(s"/v1/acls/*?self=false") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(2L, Seq((allAcls(myOrg), 3), (allAcls(myOrg2), 3)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with project path containing *" in {
      acls.append(userAcl(myOrgMyProj2), 0).accepted
      acls.append(groupAcl(myOrgMyProj2), 1).accepted
      acls.append(group2Acl(myOrgMyProj2), 2).accepted
      acls.append(group2Acl(myOrg3), 0).accepted
      Get(s"/v1/acls/myorg/*") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(2L, Seq((selfAcls(myOrgMyProj), 3), (selfAcls(myOrgMyProj2), 3)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with project path containing *" in {
      Get(s"/v1/acls/myorg/*?self=false") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(2L, Seq((allAcls(myOrgMyProj), 3), (allAcls(myOrgMyProj2), 3)))
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org and project path containing *" in {
      acls.append(userAcl(myOrg2MyProj2), 0).accepted
      acls.append(groupAcl(myOrg2MyProj2), 1).accepted
      acls.append(group2Acl(myOrg2MyProj2), 2).accepted
      Get(s"/v1/acls/*/*") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(
            3L,
            Seq((selfAcls(myOrgMyProj), 3), (selfAcls(myOrgMyProj2), 3), (selfAcls(myOrg2MyProj2), 3))
          )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org and project path containing *" in {
      Get(s"/v1/acls/*/*?self=false") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(
            3L,
            Seq((allAcls(myOrgMyProj), 3), (allAcls(myOrgMyProj2), 3), (allAcls(myOrg2MyProj2), 3))
          )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with project path containing * with ancestors" in {
      Get(s"/v1/acls/myorg/*?ancestors=true") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(
            4L,
            Seq((selfAcls(Root), 3), (selfAcls(myOrg), 3), (selfAcls(myOrgMyProj), 3), (selfAcls(myOrgMyProj2), 3))
          )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with project path containing * with ancestors" in {
      Get(s"/v1/acls/myorg/*?ancestors=true&self=false") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(
            4L,
            Seq((allAcls(Root), 3), (allAcls(myOrg), 3), (allAcls(myOrgMyProj), 3), (allAcls(myOrgMyProj2), 3))
          )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org path containing * with ancestors" in {
      Get(s"/v1/acls/*?ancestors=true") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          3L,
          Seq((selfAcls(Root), 3), (selfAcls(myOrg), 3), (selfAcls(myOrg2), 3))
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org path containing * with ancestors" in {
      Get(s"/v1/acls/*?ancestors=true&self=false") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          4L,
          Seq((allAcls(Root), 3), (allAcls(myOrg), 3), (allAcls(myOrg2), 3), (group2Acl(myOrg3), 1))
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true with org  and project path containing * with ancestors" in {
      Get(s"/v1/acls/*/*?ancestors=true") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          6L,
          Seq(
            (selfAcls(Root), 3),
            (selfAcls(myOrg), 3),
            (selfAcls(myOrgMyProj), 3),
            (selfAcls(myOrgMyProj2), 3),
            (selfAcls(myOrg2), 3),
            (selfAcls(myOrg2MyProj2), 3)
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false with org  and project path containing * with ancestors" in {
      Get(s"/v1/acls/*/*?ancestors=true&self=false") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(
          7L,
          Seq(
            (allAcls(Root), 3),
            (allAcls(myOrg), 3),
            (allAcls(myOrgMyProj), 3),
            (allAcls(myOrgMyProj2), 3),
            (allAcls(myOrg2), 3),
            (allAcls(myOrg2MyProj2), 3),
            (group2Acl(myOrg3), 1)
          )
        )
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = false and rev = 2 when response is an empty ACL" in {
      Get(s"/v1/acls/myorg/myproj1?rev=2&self=false") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual expectedResponse(0L, Seq.empty)
        status shouldEqual StatusCodes.OK
      }
    }

    "get ACL self = true and ancestors = true" in {
      Get(s"/v1/acls/myorg/myproj?ancestors=true") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual
          expectedResponse(3, Seq((selfAcls(Root), 3), (selfAcls(myOrg), 3), (selfAcls(myOrgMyProj), 3)))
        status shouldEqual StatusCodes.OK
      }
    }

    "subtract ACL" in {
      val patch = aclJson(userAclRead(Root)).removeKeys("_path") deepMerge
        Json.obj("@type" -> Json.fromString("Subtract"))
      forAll(paths) { case (path, address) =>
        Patch(s"/v1/acls$path?rev=3", patch.toEntity) ~> as(user) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, rev = 4, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "delete ACL" in {
      forAll(paths) { case (path, address) =>
        Delete(s"/v1/acls$path?rev=4") ~> as(user) ~> routes ~> check {
          response.asJson shouldEqual aclMetadata(address, rev = 5, createdBy = user, updatedBy = user)
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "return an error when getting ACL with rev and ancestors = true" in {
      Get(s"/v1/acls/myorg/myproj?rev=2&ancestors=true") ~> as(user) ~> routes ~> check {
        response.asJson shouldEqual jsonContentOf("errors/acls-malformed-query-params.json")
        status shouldEqual StatusCodes.BadRequest
      }
    }

    "return an error in the case of the keyword 'events'" in {
      Get(s"/v1/acls/events") ~> as(user) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

  }

  def aclMetadata(
      address: AclAddress,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json        =
    jsonContentOf(
      "acls/acl-route-metadata-response.json",
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.asIri,
      "updatedBy"  -> updatedBy.asIri,
      "path"       -> address,
      "project"    -> (if (address == AclAddress.Root) "" else address)
    )
}
