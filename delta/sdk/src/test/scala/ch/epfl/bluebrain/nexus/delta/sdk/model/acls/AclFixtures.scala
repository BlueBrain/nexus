package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors

trait AclFixtures extends EitherValuable with Inspectors {
  val user: Identity  = User("myuser", Label.unsafe("myrealm"))
  val group: Identity = Group("mygroup", Label.unsafe("myrealm2"))
  val anon: Identity  = Anonymous
  val r: Permission   = Permission.unsafe("acls/read")
  val w: Permission   = Permission.unsafe("acls/write")
  val x: Permission   = Permission.unsafe("acls/execute")
  val org: Label      = Label.unsafe("org")
  val proj: Label     = Label.unsafe("proj")
}

object AclFixtures extends AclFixtures
