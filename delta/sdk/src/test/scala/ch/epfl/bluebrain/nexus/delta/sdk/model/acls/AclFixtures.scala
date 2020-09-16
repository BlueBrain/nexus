package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors

trait AclFixtures extends EitherValuable with Inspectors {
  val subject: User        = User("myuser", Label.unsafe("myrealm"))
  val user: Identity       = subject
  val group: Identity      = Group("mygroup", Label.unsafe("myrealm2"))
  val anon: Identity       = Anonymous
  val r: Permission        = Permission.unsafe("acls/read")
  val w: Permission        = Permission.unsafe("acls/write")
  val x: Permission        = Permission.unsafe("acls/execute")
  val org: Label           = Label.unsafe("org")
  val proj: Label          = Label.unsafe("proj")
  val rwx: Set[Permission] = Set(r, w, x)
  val epoch                = Instant.EPOCH
  val userR                = Acl(user -> Set(r))
  val userW                = Acl(user -> Set(w))
  val userR_groupX         = Acl(user -> Set(r), group -> Set(x))
  val userRW_groupX        = Acl(user -> Set(r, w), group -> Set(x))
  val groupR               = Acl(group -> Set(r))
  val groupX               = Acl(group -> Set(x))
  val anonR                = Acl(anon -> Set(r))
}

object AclFixtures extends AclFixtures
