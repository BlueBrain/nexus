package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.AclNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import cats.effect.Ref
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller

import scala.collection.immutable

/**
  * In-memory implementation of an [[AclCheck]]
  */
abstract class AclSimpleCheck private (cache: Ref[IO, Map[AclAddress, Acl]]) extends AclCheck {

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
}

object AclSimpleCheck {

  private def emptyAclSimpleCheck: IO[AclSimpleCheck] = {
    Ref.of[IO, Map[AclAddress, Acl]](Map.empty).map { cache =>
      val aclCheck = AclCheck(
        address => cache.get.flatMap { c => IO.fromOption(c.get(address))(AclNotFound(address)) },
        cache.get
      )
      new AclSimpleCheck(cache) {
        override def authorizeForOr[E <: Throwable](
            path: AclAddress,
            permission: Permission,
            identities: Set[Identity]
        )(onError: => E): IO[Unit] =
          aclCheck.authorizeForOr(path, permission, identities)(onError)

        override def authorizeForOr[E <: Throwable](path: AclAddress, permission: Permission)(onError: => E)(implicit
            caller: Caller
        ): IO[Unit] =
          aclCheck.authorizeForOr(path, permission)(onError)

        override def authorizeFor(path: AclAddress, permission: Permission, identities: Set[Identity]): IO[Boolean] =
          aclCheck.authorizeFor(path, permission, identities)

        override def authorizeFor(path: AclAddress, permission: Permission)(implicit caller: Caller): IO[Boolean] =
          aclCheck.authorizeFor(path, permission)

        override def authorizeForEveryOr[E <: Throwable](path: AclAddress, permissions: Set[Permission])(
            onError: => E
        )(implicit caller: Caller): IO[Unit] =
          aclCheck.authorizeForEveryOr(path, permissions)(onError)

        override def mapFilterOrRaise[E, A, B](
            values: immutable.Iterable[A],
            extractAddressPermission: A => (AclAddress, Permission),
            onAuthorized: A => B,
            onFailure: AclAddress => IO[Unit]
        )(implicit caller: Caller): IO[Set[B]] =
          aclCheck.mapFilterOrRaise(values, extractAddressPermission, onAuthorized, onFailure)

        override def mapFilter[A, B](
            values: immutable.Iterable[A],
            extractAddressPermission: A => (AclAddress, Permission),
            onAuthorized: A => B
        )(implicit caller: Caller): IO[Set[B]] =
          aclCheck.mapFilter(values, extractAddressPermission, onAuthorized)

        override def mapFilterAtAddressOrRaise[E, A, B](
            values: immutable.Iterable[A],
            address: AclAddress,
            extractPermission: A => Permission,
            onAuthorized: A => B,
            onFailure: AclAddress => IO[Unit]
        )(implicit caller: Caller): IO[Set[B]] =
          aclCheck.mapFilterAtAddressOrRaise(values, address, extractPermission, onAuthorized, onFailure)

        override def mapFilterAtAddress[A, B](
            values: immutable.Iterable[A],
            address: AclAddress,
            extractPermission: A => Permission,
            onAuthorized: A => B
        )(implicit caller: Caller): IO[Set[B]] =
          aclCheck.mapFilterAtAddress(values, address, extractPermission, onAuthorized)
      }
    }
  }

  /**
    * Create an [[AclSimpleCheck]] and initializes it with the provided acls
    * @param input
    * @return
    */
  def apply(input: (Identity, AclAddress, Set[Permission])*): IO[AclSimpleCheck] =
    emptyAclSimpleCheck.flatTap { checker =>
      input.toList
        .traverse { case (subject, address, permissions) =>
          checker append (address, (subject, permissions))
        }
    }

  def unsafe(input: (Identity, AclAddress, Set[Permission])*) =
    apply(input: _*).unsafeRunSync()

}
