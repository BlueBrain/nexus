package ch.epfl.bluebrain.nexus.iam.permissions

import java.time.Instant

import cats.effect.{Clock, ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.{HttpConfig, PermissionsConfig}
import ch.epfl.bluebrain.nexus.iam.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.iam.types.IamError.AccessDenied
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission, ResourceF}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.util._
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation,NameBooleanParameters
class PermissionsSpec
    extends ActorSystemFixture("PermissionsSpec", true)
    with Matchers
    with IOEitherValues
    with IOOptionValues
    with Randomness
    with IdiomaticMockito {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 50.milliseconds)

  val appConfig: AppConfig           = Settings(system).appConfig
  implicit val http: HttpConfig      = appConfig.http
  implicit val pc: PermissionsConfig = appConfig.permissions

  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]      = IO.timer(ExecutionContext.global)
  implicit val caller: Caller        = Caller.anonymous

  val instant: Instant = Instant.ofEpochMilli(5L)
  implicit val clock: Clock[IO] = {
    val m = mock[Clock[IO]]
    m.realTime(MILLISECONDS) shouldReturn IO.pure(instant.toEpochMilli)
    m
  }

  val (macls, acls) = {
    val m = mock[Acls[IO]]
    m.hasPermission(Path./, read, ancestors = false)(caller) shouldReturn IO.pure(true)
    m.hasPermission(Path./, write, ancestors = false)(caller) shouldReturn IO.pure(true)
    (m, IO.pure(m))
  }

  val minimum = Set(
    Permission.unsafe("acls/read"),
    Permission.unsafe("acls/write"),
    Permission.unsafe("permissions/read"),
    Permission.unsafe("permissions/write"),
    Permission.unsafe("realms/read"),
    Permission.unsafe("realms/write"),
    Permission.unsafe("events/read"),
    Permission.unsafe("organizations/read"),
    Permission.unsafe("organizations/write"),
    Permission.unsafe("organizations/create"),
    Permission.unsafe("projects/read"),
    Permission.unsafe("projects/write"),
    Permission.unsafe("projects/create"),
    Permission.unsafe("resources/read"),
    Permission.unsafe("resources/write"),
    Permission.unsafe("resolvers/write"),
    Permission.unsafe("views/query"),
    Permission.unsafe("views/write"),
    Permission.unsafe("schemas/write"),
    Permission.unsafe("files/write"),
    Permission.unsafe("storages/write"),
    Permission.unsafe("archives/write")
  )

  val perm1: Permission = Permission.unsafe(genString())
  val perm2: Permission = Permission.unsafe(genString())
  val perm3: Permission = Permission.unsafe(genString())
  val perm4: Permission = Permission.unsafe(genString())

  val epoch: Instant = Instant.EPOCH
  val perms          = Permissions(acls).unsafeRunSync()

  "The Permissions API" should {
    "return the minimum permissions" in {
      perms.effectivePermissions.ioValue shouldEqual minimum
    }
    "return the minimum permissions resource" in {
      perms.fetch.ioValue shouldEqual ResourceF(id, 0L, types, epoch, Anonymous, epoch, Anonymous, minimum)
    }
    "fail to delete minimum when initial" in {
      perms.delete(0L).rejected[CannotDeleteMinimumCollection.type]
    }
    "fail to subtract with incorrect rev" in {
      perms.subtract(Set(perm1), 1L).rejected[IncorrectRev] shouldEqual IncorrectRev(1L, 0L)
    }
    "fail to subtract from minimum" in {
      perms.subtract(Set(perm1), 0L).rejected[CannotSubtractFromMinimumCollection].permissions shouldEqual minimum
    }
    "fail to subtract undefined permissions" in {
      perms.append(Set(perm1)).ioValue
      perms.subtract(Set(perm2), 1L).rejected[CannotSubtractUndefinedPermissions].permissions shouldEqual Set(perm2)
    }
    "fail to subtract empty permissions" in {
      perms.subtract(Set(), 1L).rejected[CannotSubtractEmptyCollection.type]
    }
    "fail to subtract from minimum collection" in {
      perms.subtract(Set(read), 1L).rejected[CannotSubtractFromMinimumCollection].permissions shouldEqual minimum
    }
    "subtract a permission" in {
      perms.subtract(Set(perm1), 1L).accepted
      perms.effectivePermissions.ioValue shouldEqual minimum
    }
    "fail to append with incorrect rev" in {
      perms.append(Set(perm1)).rejected[IncorrectRev] shouldEqual IncorrectRev(0L, 2L)
    }
    "append permissions" in {
      perms.append(Set(perm1, perm2), 2L).accepted
      perms.effectivePermissions.ioValue shouldEqual (minimum ++ Set(perm1, perm2))
    }
    "fail to append duplicate permissions" in {
      perms.append(Set(perm2), 3L).rejected[CannotAppendEmptyCollection.type]
    }
    "fail to append empty permissions" in {
      perms.append(Set(), 3L).rejected[CannotAppendEmptyCollection.type]
    }
    "fail to replace with incorrect rev" in {
      perms.replace(Set(perm3), 1L).rejected[IncorrectRev] shouldEqual IncorrectRev(1L, 3L)
    }
    "fail to replace with empty permissions" in {
      perms.replace(Set(), 3L).rejected[CannotReplaceWithEmptyCollection.type]
    }
    "fail to replace with subset of minimum" in {
      perms.replace(Set(read), 3L).rejected[CannotReplaceWithEmptyCollection.type]
    }
    "replace non minimum" in {
      perms.replace(Set(perm3, perm4), 3L).accepted
      perms.effectivePermissions.ioValue shouldEqual (pc.minimum ++ Set(perm3, perm4))
    }
    "fail to delete with incorrect rev" in {
      perms.delete(2L).rejected[IncorrectRev] shouldEqual IncorrectRev(2L, 4L)
    }
    "delete permissions" in {
      perms.delete(4L).accepted
      perms.effectivePermissions.ioValue shouldEqual minimum
    }
    "fail to delete minimum permissions" in {
      perms.delete(5L).rejected[CannotDeleteMinimumCollection.type]
    }
    "return some for correct rev" in {
      val value = perms.fetchAt(4L).ioValue
      value match {
        case Some(res) => res.value shouldEqual (pc.minimum ++ Set(perm3, perm4))
        case None      => fail("Permissions were not returned for a known revision")
      }
    }
    "return none for unknown rev" in {
      perms.fetchAt(9999L).ioValue shouldEqual None
    }

    // NO PERMISSIONS BEYOND THIS LINE

    "fail to fetch when no permissions" in {
      macls.hasPermission(Path./, read, ancestors = false)(caller) shouldReturn IO.pure(false)
      macls.hasPermission(Path./, write, ancestors = false)(caller) shouldReturn IO.pure(false)
      perms.fetch.failed[AccessDenied] shouldEqual AccessDenied(id, read)
      perms.effectivePermissions.failed[AccessDenied] shouldEqual AccessDenied(id, read)
    }
    "fail to append when no permissions" in {
      perms.append(Set(perm3)).failed[AccessDenied] shouldEqual AccessDenied(id, write)
    }
    "fail to subtract when no permissions" in {
      perms.subtract(Set(perm3), 1L).failed[AccessDenied] shouldEqual AccessDenied(id, write)
    }
    "fail to replace when no permissions" in {
      perms.replace(Set(perm3), 1L).failed[AccessDenied] shouldEqual AccessDenied(id, write)
    }
    "fail to delete when no permissions" in {
      perms.delete(1L).failed[AccessDenied] shouldEqual AccessDenied(id, write)
    }
  }
}
