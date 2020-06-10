package ch.epfl.bluebrain.nexus.iam.acls

import java.time.Instant

import cats.effect.{Clock, ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.iam.acls.AclRejection._
import ch.epfl.bluebrain.nexus.iam.permissions.Permissions
import ch.epfl.bluebrain.nexus.iam.types.IamError.AccessDenied
import ch.epfl.bluebrain.nexus.iam.types.Identity._
import ch.epfl.bluebrain.nexus.iam.types._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.util._
import org.mockito.IdiomaticMockito
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

//noinspection TypeAnnotation,NameBooleanParameters
class AclsSpec
    extends ActorSystemFixture("AclsSpec", true)
    with Matchers
    with IOEitherValues
    with IOOptionValues
    with Randomness
    with Inspectors
    with IdiomaticMockito {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 50.milliseconds)

  implicit val config = Settings(system).serviceConfig
  implicit val http   = config.http
  implicit val pc     = config.iam.permissions
  implicit val ac     = config.iam.acls

  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  private val identities: List[Identity]   = List(User("sub", "realm"), Group("group", "realm"), Anonymous)
  private val permissions: Set[Permission] = List.fill(300)(Permission(genString(length = 6)).value).toSet

  val (mperms, perms) = {
    val m = mock[Permissions[IO]]
    m.minimum shouldReturn config.iam.permissions.minimum
    m.effectivePermissionsUnsafe shouldReturn IO.pure(permissions)
    (m, IO.pure(m))
  }

  val instant: Instant = Instant.ofEpochSecond(3600)
  implicit val clock: Clock[IO] = {
    val m = mock[Clock[IO]]
    m.realTime(MILLISECONDS) shouldReturn IO.pure(instant.toEpochMilli)
    m
  }

  private val acls = Acls[IO](perms).ioValue

  private def pathIriString(path: Path): String =
    s"${http.publicIri.asUri}/${http.prefix}/acls${path.asString}"

  trait Context {
    val createdBy: Subject = User("sub", "realm")
    implicit val caller    = Caller(createdBy, Set[Identity](Group("admin", "realm"), Anonymous))
    val path: Path         = genString(length = 4) / genString(length = 4)
    val id: AbsoluteIri    = Iri.absolute("http://127.0.0.1:8080/v1/acls/").rightValue + path.asString
    val user1              = identities(genInt(max = 1))
    val user2              = identities.filterNot(_ == user1).head
    val permsUser1         = Random.shuffle(permissions).take(1 + genInt(max = 299))
    val permsUser2         = Random.shuffle(permissions).take(1 + genInt(max = 299))
    val acl                = AccessControlList(user1 -> permsUser1, user2 -> permsUser2)
    val unknownPerm        = Permission(genString(length = 8)).value
    val unknownAcl         = AccessControlList(user1 -> (permsUser1 + unknownPerm))
  }

  trait AppendCtx extends Context {
    val permsUser1Append = Random.shuffle(permissions -- permsUser1).take(1 + genInt(max = 299))
    val permsUser2Append = Random.shuffle(permissions -- permsUser2).take(1 + genInt(max = 299))
    val aclAppend        = AccessControlList(user1 -> permsUser1Append, user2 -> permsUser2Append)
  }

  "The Acls surface API" when {

    "performing get operations" should {
      "fetch initial ACLs" in new Context {
        acls.fetch(/, self = true).some.value shouldEqual AccessControlList(Anonymous  -> pc.minimum)
        acls.fetch(/, self = false).some.value shouldEqual AccessControlList(Anonymous -> pc.minimum)
      }

      "return none for unknown revision" in new Context {
        acls.fetch(/, 10L, self = true).ioValue shouldEqual None
        acls.fetch(/, 10L, self = false).ioValue shouldEqual None
      }

      "fetch other non existing ACLs" in new Context {
        acls.fetch(path, self = true).ioValue shouldEqual None
        acls.fetch(path, 10L, self = false).ioValue shouldEqual None
      }

      "fail to fetch by revision when using self=false without read permissions" in new Context {
        val failed = acls
          .fetch(path, 1L, self = false)(Caller(createdBy, Set(createdBy, Group("admin", genString()))))
          .failed[AccessDenied]
        failed.resource.asString shouldEqual pathIriString(path)
        failed.permission shouldEqual read
      }

      "fail to fetch when using self=false without read permissions" in new Context {
        val failed = acls
          .fetch(path, self = false)(Caller(createdBy, Set(createdBy, Group("admin", genString()))))
          .failed[AccessDenied]
        failed.resource.asString shouldEqual pathIriString(path)
        failed.permission shouldEqual read
      }
    }

    "performing replace operations" should {

      "reject when no parent acls/write permissions present" in new Context {
        val failed = acls
          .replace(path, 0L, acl)(Caller(createdBy, Set(createdBy, Group("admin", genString()))))
          .failed[AccessDenied]
        failed.resource.asString shouldEqual pathIriString(path)
        failed.permission shouldEqual write
      }

      "reject when wrong revision" in new Context {
        acls.replace(path, 1L, acl).ioValue shouldEqual Left(IncorrectRev(path, 1L, 0L))
      }

      "reject when empty permissions" in new Context {
        val emptyAcls = AccessControlList(user1 -> Set.empty, user2 -> permsUser2)
        acls.replace(path, 0L, emptyAcls).rejected[AclCannotContainEmptyPermissionCollection].path shouldEqual path
      }

      "successfully be created" in new Context {
        val metadata =
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.replace(path, 0L, acl).accepted shouldEqual metadata
        acls.fetch(path, self = false).some shouldEqual metadata.map(_ => acl)
      }

      "successfully be updated" in new Context {
        acls.replace(path, 0L, acl).accepted shouldEqual
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        val replaced         = AccessControlList(user1 -> permsUser1)
        val updatedBy        = User(genString(), genString())
        val otherIds: Caller = Caller(updatedBy, Set(Group("admin", "realm"), updatedBy, Anonymous))
        val metadata =
          ResourceMetadata(id, 2L, Set(nxv.AccessControlList.value), instant, createdBy, instant, updatedBy)
        acls.replace(path, 1L, replaced)(otherIds).accepted shouldEqual metadata
        acls.fetch(path, self = false).some shouldEqual metadata.map(_ => replaced)
      }

      "fetch the correct revision" in new Context {
        acls.replace(path, 0L, acl).accepted
        acls.replace(path, 1L, AccessControlList(user1 -> permsUser1)).accepted
        acls.fetch(path, 1L, self = false).some.value shouldEqual acl
      }

      "reject when wrong revision after updated" in new Context {
        acls.replace(path, 0L, acl).accepted shouldEqual
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)

        val replaced = AccessControlList(user1 -> permsUser1)
        forAll(List(0L, 2L, 10L)) { rev =>
          acls.replace(path, rev, replaced).rejected[IncorrectRev] shouldEqual IncorrectRev(path, rev, 1L)
        }
      }

      "reject changes with unknown permissions" in new Context {
        acls.replace(path, 0L, unknownAcl).rejected[UnknownPermissions].permissions shouldEqual Set(unknownPerm)
      }

      "accept updates with rev when ACL is empty" in new Context {
        val metadata =
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.replace(path, 0L, acl).accepted shouldEqual metadata
        acls.delete(path, 1L).accepted shouldEqual metadata.copy(rev = 2L)
        acls.replace(path, 2L, acl).accepted shouldEqual metadata.copy(rev = 3L)
      }

      "accept updates without rev when ACL is empty" in new Context {
        val metadata =
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.replace(path, 0L, acl).accepted shouldEqual metadata
        acls.delete(path, 1L).accepted shouldEqual metadata.copy(rev = 2L)
        acls.replace(path, 0L, acl).accepted shouldEqual metadata.copy(rev = 3L)
      }

      "reject updates with incorrect rev when ACL is empty" in new Context {
        val metadata =
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.replace(path, 0L, acl).accepted shouldEqual metadata
        acls.delete(path, 1L).accepted shouldEqual metadata.copy(rev = 2L)
        acls.replace(path, 5L, acl).rejected[IncorrectRev].expected shouldEqual 2L
      }
    }

    "performing append operations" should {

      "reject when trying to append the already existing ACL" in new Context {
        acls.replace(path, 0L, acl).accepted

        acls.append(path, 1L, acl).rejected[NothingToBeUpdated].path shouldEqual path
      }

      "reject when trying to append the partially already existing ACL" in new AppendCtx {
        val _      = acls.replace(path, 0L, acl).accepted
        val append = AccessControlList(user1 -> permsUser1)
        acls.append(path, 1L, append).rejected[NothingToBeUpdated].path shouldEqual path
      }

      "reject when no parent acls/write permissions present" in new AppendCtx {
        acls.replace(path, 0L, acl).accepted

        val failed = acls
          .append(path, 1L, aclAppend)(Caller(createdBy, Set(createdBy, Group("admin", genString()))))
          .failed[AccessDenied]
        failed.resource.asString shouldEqual pathIriString(path)
        failed.permission shouldEqual write
      }

      "reject when wrong revision" in new AppendCtx {
        acls.replace(path, 0L, acl).accepted

        forAll(List(0L, 2L, 10L)) { rev =>
          val rej = acls.append(path, rev, aclAppend).rejected[IncorrectRev]
          rej.path shouldEqual path
          rej.provided shouldEqual rev
          rej.expected shouldEqual 1L
        }
      }

      "reject when empty permissions" in new AppendCtx {
        acls.replace(path, 0L, acl).accepted

        val emptyAcls = AccessControlList(user1 -> Set.empty, user2 -> permsUser2)
        acls.append(path, 1L, emptyAcls).rejected[AclCannotContainEmptyPermissionCollection].path shouldEqual path
      }

      "successfully be appended" in new AppendCtx {
        acls.replace(path, 0L, acl).accepted

        val metadata =
          ResourceMetadata(id, 2L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.append(path, 1L, aclAppend).accepted shouldEqual metadata

        acls.fetch(path, self = false).some shouldEqual metadata.map(_ => aclAppend ++ acl)

      }

      "reject changes with unknown permissions" in new AppendCtx {
        acls.append(path, 0L, unknownAcl).rejected[UnknownPermissions].permissions shouldEqual Set(unknownPerm)
      }

      "accept appends with rev when ACL is empty" in new Context {
        val metadata =
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.replace(path, 0L, acl).accepted shouldEqual metadata
        acls.delete(path, 1L).accepted shouldEqual metadata.copy(rev = 2L)
        acls.append(path, 2L, acl).accepted shouldEqual metadata.copy(rev = 3L)
      }

      "accept appends without rev when ACL is empty" in new Context {
        val metadata =
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.replace(path, 0L, acl).accepted shouldEqual metadata
        acls.delete(path, 1L).accepted shouldEqual metadata.copy(rev = 2L)
        acls.append(path, 0L, acl).accepted shouldEqual metadata.copy(rev = 3L)
      }

      "reject appends with incorrect rev when ACL is empty" in new Context {
        val metadata =
          ResourceMetadata(id, 1L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.replace(path, 0L, acl).accepted shouldEqual metadata
        acls.delete(path, 1L).accepted shouldEqual metadata.copy(rev = 2L)
        acls.append(path, 5L, acl).rejected[IncorrectRev].expected shouldEqual 2L
      }
    }

    "performing subtract operations" should {

      "reject when trying to subtract nonExisting ACL" in new Context {
        acls.replace(path, 0L, acl).accepted
        val nonExisting =
          AccessControlList(user1 -> Set(Permission(genString()).value), user2 -> Set(Permission(genString()).value))
        acls.subtract(path, 1L, nonExisting).rejected[NothingToBeUpdated].path shouldEqual path
      }

      "reject when no parent acls/write permissions present" in new Context {
        acls.replace(path, 0L, acl).accepted

        val failed = acls
          .subtract(path, 1L, acl)(Caller(createdBy, Set(createdBy, Group("admin", genString()))))
          .failed[AccessDenied]
        failed.resource.asString shouldEqual pathIriString(path)
        failed.permission shouldEqual write
      }

      "reject when wrong revision" in new Context {
        acls.replace(path, 0L, acl).accepted

        forAll(List(0L, 2L, 10L)) { rev =>
          acls.subtract(path, rev, acl).rejected[IncorrectRev] shouldEqual IncorrectRev(path, rev, 1L)
        }
      }

      "reject when empty permissions" in new Context {
        acls.replace(path, 0L, acl).accepted

        val emptyAcls = AccessControlList(user1 -> Set.empty, user2 -> permsUser2)
        acls.subtract(path, 1L, emptyAcls).rejected[AclCannotContainEmptyPermissionCollection].path shouldEqual path
      }

      "successfully be subtracted" in new Context {
        acls.replace(path, 0L, acl).accepted

        val metadata =
          ResourceMetadata(id, 2L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.subtract(path, 1L, acl).accepted shouldEqual metadata

        acls.fetch(path, self = false).some shouldEqual metadata.map(_ => AccessControlList.empty)
      }

      "reject changes with unknown permissions" in new AppendCtx {
        acls.replace(path, 0L, acl).accepted
        acls.subtract(path, 1L, unknownAcl).rejected[UnknownPermissions].permissions shouldEqual Set(unknownPerm)
      }
    }

    "performing delete operations" should {

      "reject when already deleted" in new Context {
        acls.replace(path, 0L, acl).accepted
        acls.subtract(path, 1L, acl).accepted
        acls.delete(path, 2L).rejected[AclIsEmpty].path shouldEqual path
      }

      "reject when no parent acls/write permissions present" in new Context {
        acls.replace(path, 0L, acl).accepted

        val failed = acls
          .delete(path, 1L)(Caller(createdBy, Set(createdBy, Group("admin", genString()))))
          .failed[AccessDenied]
        failed.resource.asString shouldEqual pathIriString(path)
        failed.permission shouldEqual write
      }

      "reject when wrong revision" in new Context {
        acls.replace(path, 0L, acl).accepted

        forAll(List(0L, 2L, 10L)) { rev =>
          acls.delete(path, rev).rejected[IncorrectRev] shouldEqual IncorrectRev(path, rev, 1L)
        }
      }

      "successfully be deleted" in new Context {
        acls.replace(path, 0L, acl).accepted

        val metadata =
          ResourceMetadata(id, 2L, Set(nxv.AccessControlList.value), instant, createdBy, instant, createdBy)
        acls.delete(path, 1L).ioValue shouldEqual
          Right(metadata)

        acls.fetch(path, self = false).some shouldEqual metadata.map(_ => AccessControlList.empty)
      }
    }
  }
}
