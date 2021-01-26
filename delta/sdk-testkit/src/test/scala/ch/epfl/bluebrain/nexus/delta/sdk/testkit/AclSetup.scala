package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{IdentityRealm, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import monix.bio.UIO

object AclSetup {

  val service: User                   = User("service", Label.unsafe("internal"))
  implicit val serviceAccount: Caller = Caller(service, Set(service))

  /**
    * Set up Acls and PermissionsDummy and init some acls for the given users
    * @param input the acls to create
    */
  def init(input: (Subject, AclAddress, Set[Permission])*): UIO[Acls] = {
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
    } yield acls
  }

}
