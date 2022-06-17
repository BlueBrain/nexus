package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.concurrent.Ref
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddressFilter.AnyOrganizationAnyProject
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.AclNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import monix.bio.{IO, Task, UIO}

import scala.collection.immutable.Iterable

/**
  * Check authorizations on acls
  */
trait AclCheck {

  def fetchOne: AclAddress => IO[AclNotFound, Acl]

  def fetchAll: UIO[Map[AclAddress, Acl]]

  private def authorizeForOr[E](
      path: AclAddress,
      permission: Permission,
      identities: Set[Identity],
      f: AclAddress => IO[AclNotFound, Acl]
  )(
      onError: => E
  ): IO[E, Unit] =
    path.ancestors
      .foldM(false) {
        case (false, address) => f(address).redeem(_ => false, _.hasPermission(identities, permission))
        case (true, _)        => UIO.pure(true)
      }
      .flatMap { result => IO.raiseWhen(!result)(onError) }

  /**
    * Checks whether the provided entities has the passed ''permission'' on the passed ''path'', raising the error
    * ''onError'' when it doesn't
    */
  def authorizeForOr[E](path: AclAddress, permission: Permission, identities: Set[Identity])(
      onError: => E
  ): IO[E, Unit] =
    authorizeForOr(path, permission, identities, fetchOne)(onError)

  /**
    * Checks whether a given [[Caller]] has the passed ''permission'' on the passed ''path'', raising the error
    * ''onError'' when it doesn't
    */
  def authorizeForOr[E](path: AclAddress, permission: Permission)(onError: => E)(implicit caller: Caller): IO[E, Unit] =
    authorizeForOr(path, permission, caller.identities)(onError)

  /**
    * Checks whether the provided entities have the passed ''permission'' on the passed ''path''.
    */
  def authorizeFor(
      path: AclAddress,
      permission: Permission,
      identities: Set[Identity],
      f: AclAddress => IO[AclNotFound, Acl]
  ): UIO[Boolean] =
    authorizeForOr(path, permission, identities, f)(false).redeem(identity, _ => true)

  /**
    * Checks whether the provided entities have the passed ''permission'' on the passed ''path''.
    */
  def authorizeFor(path: AclAddress, permission: Permission, identities: Set[Identity]): UIO[Boolean] =
    authorizeForOr(path, permission, identities)(false).redeem(identity, _ => true)

  /**
    * Checks whether a given [[Caller]] has the passed ''permission'' on the passed ''path''.
    */
  def authorizeFor(path: AclAddress, permission: Permission)(implicit caller: Caller): UIO[Boolean] =
    authorizeFor(path, permission, caller.identities)

  /**
    * Checks whether a given [[Caller]] has all the passed ''permissions'' on the passed ''path'', raising the error
    * ''onError'' when it doesn't
    */
  def authorizeForEveryOr[E](path: AclAddress, permissions: Set[Permission])(
      onError: => E
  )(implicit caller: Caller): IO[E, Unit] =
    path.ancestors
      .foldM((false, Set.empty[Permission])) {
        case ((true, set), _)        => UIO.pure((true, set))
        case ((false, set), address) =>
          fetchOne(address)
            .redeem(
              _ => (false, set),
              { res =>
                val resSet =
                  res.value // fetch the set of permissions defined in the resource that apply to the caller
                    .filter { case (identity, _) => caller.identities.contains(identity) }
                    .values
                    .foldLeft(Set.empty[Permission])(_ ++ _)
                val sum = set ++ resSet // add it to the accumulated set
                (permissions.forall(sum.contains), sum) // true if all permissions are found, recurse otherwise
              }
            )
      }
      .flatMap { case (result, _) => IO.raiseWhen(!result)(onError) }

  /**
    * Map authorized values for the provided caller.
    *
    * Will raise an error [[E]] at the first unauthorized attempt
    *
    * @param values
    *   the list of couples addres permission to check
    * @param extractAddressPermission
    *   Extract an acl address and permission from a value [[A]]
    * @param onAuthorized
    *   to map the value [[A]] to [[B]] if access is granted
    * @param onFailure
    *   to raise an error at the firt unauthorized value
    */
  def mapFilterOrRaise[E, A, B](
      values: Iterable[A],
      extractAddressPermission: A => (AclAddress, Permission),
      onAuthorized: A => B,
      onFailure: AclAddress => IO[E, Unit]
  )(implicit caller: Caller): IO[E, Set[B]] = {
    def fetch = (address: AclAddress) =>
      fetchAll.memoize.flatMap { m =>
        IO.fromOption(m.get(address), AclNotFound(address))
      }
    values.toList.foldLeftM(Set.empty[B]) { case (acc, value) =>
      val (address, permission) = extractAddressPermission(value)
      authorizeFor(address, permission, caller.identities, fetch).flatMap { success =>
        if (success)
          IO.pure(acc + onAuthorized(value))
        else
          onFailure(address) >> IO.pure(acc)
      }
    }
  }

  /**
    * Map authorized values for the provided caller while fitering out the unauthorized ones.
    *
    * @param values
    *   the values to work on
    * @param extractAddressPermission
    *   Extract an acl address and permission from a value [[A]]
    * @param onAuthorized
    *   to map the value [[A]] to [[B]] if access is granted
    */
  def mapFilter[A, B](
      values: Iterable[A],
      extractAddressPermission: A => (AclAddress, Permission),
      onAuthorized: A => B
  )(implicit caller: Caller): UIO[Set[B]] =
    mapFilterOrRaise(values, extractAddressPermission, onAuthorized, _ => IO.unit)

  /**
    * Map authorized values for the provided caller.
    *
    * Will raise an error [[E]] at the first unauthorized attempt
    *
    * @param values
    *   the list of couples addres permission to check
    * @param address
    *   the address to check for
    * @param extractPermission
    *   Extract an acl address and permission from a value [[A]]
    * @param onAuthorized
    *   to map the value [[A]] to [[B]] if access is granted
    * @param onFailure
    *   to raise an error at the firt unauthorized value
    */
  def mapFilterAtAddressOrRaise[E, A, B](
      values: Iterable[A],
      address: AclAddress,
      extractPermission: A => Permission,
      onAuthorized: A => B,
      onFailure: AclAddress => IO[E, Unit]
  )(implicit caller: Caller): IO[E, Set[B]] =
    Ref.of[Task, Map[AclAddress, Acl]](Map.empty).hideErrors.flatMap { cache =>
      def fetch: AclAddress => IO[AclNotFound, Acl] = (address: AclAddress) =>
        cache.get.hideErrors.map(_.get(address)).flatMap {
          case Some(acl) => IO.pure(acl)
          case None      => fetchOne(address).tapEval { acl => cache.update { _.updated(address, acl) }.hideErrors }
        }

      values.toList.foldLeftM(Set.empty[B]) { case (acc, value) =>
        val permission = extractPermission(value)
        authorizeFor(address, permission, caller.identities, fetch).flatMap { success =>
          if (success)
            IO.pure(acc + onAuthorized(value))
          else
            onFailure(address) >> IO.pure(acc)
        }
      }
    }

  /**
    * Map authorized values for the provided caller while fitering out the unauthorized ones.
    *
    * @param values
    *   the list of couples addres permission to check
    * @param address
    *   the address to check for
    * @param extractPermission
    *   Extract an acl address and permission from a value [[A]]
    * @param onAuthorized
    *   to map the value [[A]] to [[B]] if access is granted
    */
  def mapFilterAtAddress[A, B](
      values: Iterable[A],
      address: AclAddress,
      extractPermission: A => Permission,
      onAuthorized: A => B
  )(implicit caller: Caller): UIO[Set[B]] =
    mapFilterAtAddressOrRaise(values, address, extractPermission, onAuthorized, _ => IO.unit)
}

object AclCheck {

  def apply(acls: Acls): AclCheck = new AclCheck {
    override def fetchOne: AclAddress => IO[AclNotFound, Acl] = acls.fetch(_).map(_.value)

    override def fetchAll: UIO[Map[AclAddress, Acl]] =
      acls
        .list(AnyOrganizationAnyProject(true))
        .map(_.value.map { case (address, resource) => address -> resource.value })
  }

}
