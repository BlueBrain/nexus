package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{acls, views}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors

trait AclFixtures extends EitherValuable with Inspectors {
  val subject: User        = User("myuser", Label.unsafe("myrealm"))
  val anon: Anonymous      = Anonymous
  val group: Group         = Group("mygroup", Label.unsafe("myrealm2"))
  val r: Permission        = acls.read
  val w: Permission        = acls.write
  val x: Permission        = views.query
  val org: Label           = Label.unsafe("org")
  val proj: Label          = Label.unsafe("proj")
  val rwx: Set[Permission] = Set(r, w, x)
  val epoch                = Instant.EPOCH
  val userR                = Acl(subject -> Set(r))
  val userW                = Acl(subject -> Set(w))
  val userRW               = Acl(subject -> Set(r, w))
  val userR_groupX         = Acl(subject -> Set(r), group -> Set(x))
  val userRW_groupX        = Acl(subject -> Set(r, w), group -> Set(x))
  val groupR               = Acl(group -> Set(r))
  val groupX               = Acl(group -> Set(x))
  val anonR                = Acl(anon -> Set(r))
}

object AclFixtures extends AclFixtures
