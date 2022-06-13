package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{IdentityRealm, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import monix.bio.UIO

object AclSetup extends IOFixedClock {

  implicit val serviceAccount: User = User("service", Label.unsafe("internal"))

  /**
    * Init realms and permissions for the ACLs
    */
  def init(permissions: Set[Permission], realmLabels: Set[Label]): UIO[Acls] =
    for {
      realms <- RealmSetup.init(realmLabels.toSeq: _*)
      acls   <- AclsDummy(permissions, realms)
    } yield acls

  /**
    * Set up Acls and PermissionsDummy and init some acls for the given users
    * @param input
    *   the acls to create
    */
  def init(input: (Subject, AclAddress, Set[Permission])*): UIO[Acls] = {
    val allPermissions = input.foldLeft(Set.empty[Permission])(_ ++ _._3)
    for {
      realms <- RealmSetup.init(input.map(_._1).collect { case id: IdentityRealm => id.realm }: _*)
      acls   <- AclsDummy(allPermissions, realms)
      _      <- input.toList
                  .traverse { case (subject, address, permissions) =>
                    acls.fetch(address).redeem(_ => 0L, _.rev).flatMap { rev =>
                      acls.append(Acl(address, subject -> permissions), rev)
                    }
                  }
                  .hideErrorsWith(r => new IllegalStateException(r.reason))
    } yield acls
  }

}
