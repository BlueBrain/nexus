package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.AclNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.testkit.IORef
import monix.bio.{IO, UIO}

/**
  * In-memory implementation of an [[AclCheck]]
  */
final class AclSimpleCheck private (cache: IORef[Map[AclAddress, Acl]]) extends AclCheck {

  def append(acl: Acl): UIO[Unit] =
    cache.updateAndGet { c =>
      c.updatedWith(acl.address)(_.map(_ ++ acl).orElse(Some(acl)))
    }.void

  def append(address: AclAddress, acl: (Identity, Set[Permission])*): UIO[Unit] =
    append(Acl(address, acl.toMap))

  def replace(address: AclAddress, acl: (Identity, Set[Permission])*): UIO[Unit] =
    cache.updateAndGet { _.updated(address, Acl(address, acl.toMap)) }.void

  def delete(address: AclAddress): UIO[Unit] =
    cache.updateAndGet { _.removed(address) }.void

  def subtract(address: AclAddress, acl: (Identity, Set[Permission])*): UIO[Unit] =
    cache.updateAndGet { c =>
      val newAcl = Acl(address, acl.toMap)
      c.updatedWith(address)(_.map(_ -- newAcl).orElse(Some(newAcl)))
    }.void

  override def fetchOne: AclAddress => IO[AclRejection.AclNotFound, Acl] = (address: AclAddress) =>
    cache.get.flatMap { c =>
      IO.fromOption(c.get(address), AclNotFound(address))
    }

  override def fetchAll: UIO[Map[AclAddress, Acl]] = cache.get
}

object AclSimpleCheck {

  /**
    * Create an [[AclSimpleCheck]] and initializes it with the provided acls
    * @param input
    * @return
    */
  def apply(input: (Identity, AclAddress, Set[Permission])*): UIO[AclSimpleCheck] =
    IORef.of[Map[AclAddress, Acl]](Map.empty).map(new AclSimpleCheck(_)).tapEval { checker =>
      input.toList
        .traverse { case (subject, address, permissions) =>
          checker append (address, (subject, permissions))
        }
    }

}
