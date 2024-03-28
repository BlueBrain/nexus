package ch.epfl.bluebrain.nexus.ship.acls

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity

import scala.collection.immutable

object AclWiring {

  def alwaysAuthorize: AclCheck = new AclCheck {
    override def authorizeForOr[E <: Throwable](path: AclAddress, permission: Permission, identities: Set[Identity])(
        onError: => E
    ): IO[Unit] = IO.unit

    override def authorizeFor(path: AclAddress, permission: Permission, identities: Set[Identity]): IO[Boolean] =
      IO.pure(true)

    override def authorizeForEveryOr[E <: Throwable](path: AclAddress, permissions: Set[Permission])(onError: => E)(
        implicit caller: Caller
    ): IO[Unit] = IO.unit

    override def mapFilterOrRaise[A, B](
        values: immutable.Iterable[A],
        extractAddressPermission: A => (AclAddress, Permission),
        onAuthorized: A => B,
        onFailure: AclAddress => IO[Unit]
    )(implicit caller: Caller): IO[Set[B]] =
      IO.pure(values.map(onAuthorized).toSet)

    override def mapFilterAtAddressOrRaise[A, B](
        values: immutable.Iterable[A],
        address: AclAddress,
        extractPermission: A => Permission,
        onAuthorized: A => B,
        onFailure: AclAddress => IO[Unit]
    )(implicit caller: Caller): IO[Set[B]] =
      IO.pure(values.map(onAuthorized).toSet)
  }

}
