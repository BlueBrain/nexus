package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{acls, resolvers}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors

import java.time.Instant

trait AclFixtures extends EitherValuable with Inspectors {
  implicit val base: BaseUri                  = BaseUri("http://localhost", Label.unsafe("v1"))
  val subject: User                           = User("myuser", Label.unsafe("myrealm"))
  val anon: Anonymous                         = Anonymous
  val group: Group                            = Group("mygroup", Label.unsafe("myrealm2"))
  val r: Permission                           = acls.read
  val w: Permission                           = acls.write
  val x: Permission                           = resolvers.read
  val org: Label                              = Label.unsafe("org")
  val proj: Label                             = Label.unsafe("proj")
  val rwx: Set[Permission]                    = Set(r, w, x)
  val epoch: Instant                          = Instant.EPOCH
  def userR(address: AclAddress): Acl         = Acl(address, subject -> Set(r))
  def userW(address: AclAddress): Acl         = Acl(address, subject -> Set(w))
  def userRW(address: AclAddress): Acl        = Acl(address, subject -> Set(r, w))
  def userR_groupX(address: AclAddress): Acl  = Acl(address, subject -> Set(r), group -> Set(x))
  def userRW_groupX(address: AclAddress): Acl = Acl(address, subject -> Set(r, w), group -> Set(x))
  def groupR(address: AclAddress): Acl        = Acl(address, group -> Set(r))
  def groupX(address: AclAddress): Acl        = Acl(address, group -> Set(x))
  def anonR(address: AclAddress): Acl         = Acl(address, anon -> Set(r))
}

object AclFixtures extends AclFixtures
