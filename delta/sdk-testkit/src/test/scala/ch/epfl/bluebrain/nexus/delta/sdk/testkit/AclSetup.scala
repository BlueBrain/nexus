package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Permissions}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{IdentityRealm, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import monix.bio.UIO

object AclSetup extends IOFixedClock {

  val service: User                   = User("service", Label.unsafe("internal"))
  implicit val serviceAccount: Caller = Caller(service, Set(service))

  /**
    * Init realms and permissions for the ACLs
    */
  def init(permissions: Set[Permission], realmLabels: Set[Label]): UIO[Acls] =
    initWithPerms(permissions, realmLabels).map(_._1)

  def initWithPerms(permissions: Set[Permission], realmLabels: Set[Label]): UIO[(Acls, Permissions)] =
    for {
      perms  <- PermissionsDummy(permissions)
      realms <- RealmSetup.init(realmLabels.toSeq: _*)
      acls   <- AclsDummy(perms, realms)
    } yield (acls, perms)

  /**
    * Set up Acls and PermissionsDummy and init some acls for the given users
    * @param input
    *   the acls to create
    */
  def initValuesWithPerms(input: (Subject, AclAddress, Set[Permission])*): UIO[(Acls, Permissions)] = {
    val allPermissions = input.foldLeft(Set.empty[Permission])(_ ++ _._3)
    for {
      perms  <- PermissionsDummy(allPermissions)
      realms <- RealmSetup.init(input.map(_._1).collect { case id: IdentityRealm => id.realm }: _*)
      acls   <- AclsDummy(perms, realms)
      _      <- input.toList
                  .traverse { case (subject, address, permissions) =>
                    acls.fetch(address).redeem(_ => 0L, _.rev).flatMap { rev =>
                      acls.append(Acl(address, subject -> permissions), rev)
                    }
                  }
                  .hideErrorsWith(r => new IllegalStateException(r.reason))
    } yield acls -> perms
  }

  /**
    * Set up Acls and PermissionsDummy and init some acls for the given users
    * @param input
    *   the acls to create
    */
  def init(input: (Subject, AclAddress, Set[Permission])*): UIO[Acls] =
    initValuesWithPerms(input: _*).map(_._1)

}
