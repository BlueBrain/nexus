package ch.epfl.bluebrain.nexus.acl

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.acls.AclTarget.{OrgAcl, ProjectAcl, RootAcl}
import ch.epfl.bluebrain.nexus.acls.{AccessControlList, AccessControlLists, AclCache}
import ch.epfl.bluebrain.nexus.auth.Identity
import ch.epfl.bluebrain.nexus.auth.Identity._
import ch.epfl.bluebrain.nexus.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.config.Settings
import ch.epfl.bluebrain.nexus.permissions.Permission
import ch.epfl.bluebrain.nexus.util.{ActorSystemFixture, IOValues, Randomness}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class AclCacheSpec
    extends ActorSystemFixture("AclCacheSpec", true)
    with Matchers
    with Inspectors
    with IOValues
    with Randomness {

  private val any: String                          = "*"
  private val appConfig                            = Settings(system).appConfig
  implicit private val keyCfg: KeyValueStoreConfig = appConfig.acls.keyValueStore
  implicit private val ec: ExecutionContext        = system.dispatcher
  implicit private val cs: ContextShift[IO]        = IO.contextShift(ec)
  implicit private val tm: Timer[IO]               = IO.timer(ec)
  private val cache: AclCache[IO]                  = AclCache[IO]

  "An AclCache" should {
    val user: Identity  = User(genString(), "realm")
    val group: Identity = Group(genString(), "realm")
    val anon: Identity  = Anonymous

    val read: Permission  = Permission.unsafe("read")
    val write: Permission = Permission.unsafe("write")
    val own: Permission   = Permission.unsafe("own")

    val rootAcl    = AccessControlList(anon  -> Set(read), user -> Set(read, write))
    val orgAcl     = AccessControlList(group -> Set(write))
    val projectAcl = AccessControlList(user  -> Set(own))
    val acls       = Map(RootAcl             -> rootAcl, OrgAcl("myorg") -> orgAcl, ProjectAcl("myorg", "myproj") -> projectAcl)

    "cache ACLs" in {
      forAll(acls) {
        case (target, acls) => cache.replace(target, (acls, 0L)).ioValue
      }
    }

    "fetch ACLs" in {
      forAll(acls) {
        case (target, acls) => cache.get(target).ioValue shouldEqual AccessControlLists(target -> acls)
      }
    }

    "fetch ACLs with ancestors" in {
      cache.get(ProjectAcl("myorg", "myproj"), includeAncestors = true).ioValue shouldEqual AccessControlLists(acls)
      cache.get(ProjectAcl("myorg", any), includeAncestors = true).ioValue shouldEqual AccessControlLists(acls)
      cache.get(ProjectAcl(any, any), includeAncestors = true).ioValue shouldEqual AccessControlLists(acls)
      cache.get(ProjectAcl(genString(), genString()), includeAncestors = true).ioValue shouldEqual
        AccessControlLists(RootAcl -> rootAcl)
      cache.get(ProjectAcl("myorg", genString()), includeAncestors = true).ioValue shouldEqual
        AccessControlLists(RootAcl -> rootAcl, OrgAcl("myorg") -> orgAcl)

      cache.get(OrgAcl("myorg"), includeAncestors = true).ioValue shouldEqual
        AccessControlLists(RootAcl -> rootAcl, OrgAcl("myorg") -> orgAcl)
      cache.get(OrgAcl(any), includeAncestors = true).ioValue shouldEqual
        AccessControlLists(RootAcl -> rootAcl, OrgAcl("myorg") -> orgAcl)

      cache.get(RootAcl, includeAncestors = true).ioValue shouldEqual AccessControlLists(RootAcl -> rootAcl)
    }
  }
}
