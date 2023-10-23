package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.AclNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity

/**
  * In-memory implementation of an [[AclCheck]]
  */
final class AclSimpleCheck private (cache: Ref[IO, Map[AclAddress, Acl]]) extends AclCheck {

  def append(acl: Acl): IO[Unit] =
    cache.updateAndGet { c =>
      c.updatedWith(acl.address)(_.map(_ ++ acl).orElse(Some(acl)))
    }.void

  def append(address: AclAddress, acl: (Identity, Set[Permission])*): IO[Unit] =
    append(Acl(address, acl.toMap))

  def replace(address: AclAddress, acl: (Identity, Set[Permission])*): IO[Unit] =
    cache.updateAndGet { _.updated(address, Acl(address, acl.toMap)) }.void

  def delete(address: AclAddress): IO[Unit] =
    cache.updateAndGet { _.removed(address) }.void

  def subtract(address: AclAddress, acl: (Identity, Set[Permission])*): IO[Unit] =
    cache.updateAndGet { c =>
      val newAcl = Acl(address, acl.toMap)
      c.updatedWith(address)(_.map(_ -- newAcl).orElse(Some(newAcl)))
    }.void

  override def fetchOne: AclAddress => IO[Acl] = (address: AclAddress) =>
    cache.get.flatMap { c =>
      IO.fromOption(c.get(address))(AclNotFound(address))
    }

  override def fetchAll: IO[Map[AclAddress, Acl]] = cache.get
}

object AclSimpleCheck {

  /**
    * Create an [[AclSimpleCheck]] and initializes it with the provided acls
    * @param input
    * @return
    */
  def apply(input: (Identity, AclAddress, Set[Permission])*): IO[AclSimpleCheck] =
    Ref.of[IO, Map[AclAddress, Acl]](Map.empty).map(new AclSimpleCheck(_)).flatTap { checker =>
      input.toList
        .traverse { case (subject, address, permissions) =>
          checker append (address, (subject, permissions))
        }
    }

  def unsafe(input: (Identity, AclAddress, Set[Permission])*) =
    apply(input: _*).unsafeRunSync()

}
