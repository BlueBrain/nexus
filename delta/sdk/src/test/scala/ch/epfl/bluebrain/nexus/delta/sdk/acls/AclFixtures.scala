package ch.epfl.bluebrain.nexus.delta.sdk.acls

import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{acls, resolvers}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.Inspectors

import java.time.Instant

trait AclFixtures extends EitherValuable with Inspectors {
  implicit val base: BaseUri                  = BaseUri("http://localhost", Label.unsafe("v1"))
  val realm: Label                            = Label.unsafe("myrealm")
  val realm2: Label                           = Label.unsafe("myrealm2")
  val subject: User                           = User("myuser", realm)
  val anon: Anonymous                         = Anonymous
  val group: Group                            = Group("mygroup", realm2)
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
