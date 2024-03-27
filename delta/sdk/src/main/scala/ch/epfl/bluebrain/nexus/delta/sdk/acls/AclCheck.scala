package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.{IO, Ref}
import cats.implicits.toFoldableOps
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddressFilter.AnyOrganizationAnyProject
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.AclNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity

import scala.collection.immutable.Iterable

trait AclCheck {

  /**
    * Checks whether the provided entities has the passed ''permission'' on the passed ''path'', raising the error
    * ''onError'' when it doesn't
    */
  def authorizeForOr[E <: Throwable](path: AclAddress, permission: Permission, identities: Set[Identity])(
      onError: => E
  ): IO[Unit]

  /**
    * Checks whether a given [[Caller]] has the passed ''permission'' on the passed ''path'', raising the error
    * ''onError'' when it doesn't
    */
  def authorizeForOr[E <: Throwable](path: AclAddress, permission: Permission)(onError: => E)(implicit
      caller: Caller
  ): IO[Unit] =
    authorizeForOr(path, permission, caller.identities)(onError)

  /**
    * Checks whether the provided entities have the passed ''permission'' on the passed ''path''.
    */
  def authorizeFor(path: AclAddress, permission: Permission, identities: Set[Identity]): IO[Boolean]

  /**
    * Checks whether a given [[Caller]] has the passed ''permission'' on the passed ''path''.
    */
  def authorizeFor(path: AclAddress, permission: Permission)(implicit caller: Caller): IO[Boolean] =
    authorizeFor(path, permission, caller.identities)

  /**
    * Checks whether a given [[Caller]] has all the passed ''permissions'' on the passed ''path'', raising the error
    * ''onError'' when it doesn't
    */
  def authorizeForEveryOr[E <: Throwable](path: AclAddress, permissions: Set[Permission])(
      onError: => E
  )(implicit caller: Caller): IO[Unit]

  /**
    * Map authorized values for the provided caller.
    *
    * @param values
    *   the list of couples address permission to check
    * @param extractAddressPermission
    *   Extract an acl address and permission from a value [[A]]
    * @param onAuthorized
    *   to map the value [[A]] to [[B]] if access is granted
    * @param onFailure
    *   to raise an error at the first unauthorized value
    */
  def mapFilterOrRaise[A, B](
      values: Iterable[A],
      extractAddressPermission: A => (AclAddress, Permission),
      onAuthorized: A => B,
      onFailure: AclAddress => IO[Unit]
  )(implicit caller: Caller): IO[Set[B]]

  /**
    * Map authorized values for the provided caller while filtering out the unauthorized ones.
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
  )(implicit caller: Caller): IO[Set[B]] =
    mapFilterOrRaise(values, extractAddressPermission, onAuthorized, _ => IO.unit)

  /**
    * Map authorized values for the provided caller while filtering out the unauthorized ones.
    *
    * @param values
    *   the list of couples address permission to check
    * @param address
    *   the address to check for
    * @param extractPermission
    *   Extract an acl address and permission from a value [[A]]
    * @param onAuthorized
    *   to map the value [[A]] to [[B]] if access is granted
    */
  def mapFilterAtAddressOrRaise[A, B](
      values: Iterable[A],
      address: AclAddress,
      extractPermission: A => Permission,
      onAuthorized: A => B,
      onFailure: AclAddress => IO[Unit]
  )(implicit caller: Caller): IO[Set[B]]

  /**
    * Map authorized values for the provided caller while filtering out the unauthorized ones.
    *
    * @param values
    *   the list of couples address permission to check
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
  )(implicit caller: Caller): IO[Set[B]] =
    mapFilterAtAddressOrRaise(values, address, extractPermission, onAuthorized, _ => IO.unit)

}

object AclCheck {

  def apply(acls: Acls): AclCheck =
    apply(
      acls.fetch(_).map(_.value),
      acls
        .list(AnyOrganizationAnyProject(true))
        .map(_.value.map { case (address, resource) => address -> resource.value })
    )

  def apply(
      fetchOne: AclAddress => IO[Acl],
      fetchAll: IO[Map[AclAddress, Acl]]
  ): AclCheck = new AclCheck {

    def authorizeForOrFail[E <: Throwable](
        path: AclAddress,
        permission: Permission,
        identities: Set[Identity],
        f: AclAddress => IO[Acl]
    )(
        onError: => E
    ): IO[Unit] =
      authorizeFor(path, permission, identities, f)
        .flatMap { result => IO.raiseWhen(!result)(onError) }

    /**
      * Checks whether the provided entities have the passed ''permission'' on the passed ''path''.
      */
    def authorizeFor(
        path: AclAddress,
        permission: Permission,
        identities: Set[Identity],
        f: AclAddress => IO[Acl]
    ): IO[Boolean] =
      path.ancestors
        .foldM(false) {
          case (false, address) => f(address).redeem(_ => false, _.hasPermission(identities, permission))
          case (true, _)        => IO.pure(true)
        }

    /**
      * Checkswhether a given [[Caller]] has the passed ''permission'' on the passed ''path''.
      */
    def authorizeFor(
        path: AclAddress,
        permission: Permission,
        acls: Map[AclAddress, Acl]
    )(implicit caller: Caller): IO[Boolean] = {
      def fetch = (address: AclAddress) => IO.fromOption(acls.get(address))(AclNotFound(address))
      authorizeFor(path, permission, caller.identities, fetch)
    }

    override def authorizeForOr[E <: Throwable](path: AclAddress, permission: Permission, identities: Set[Identity])(
        onError: => E
    ): IO[Unit] = authorizeForOrFail(path, permission, identities, fetchOne)(onError)

    override def authorizeFor(path: AclAddress, permission: Permission, identities: Set[Identity]): IO[Boolean] =
      authorizeFor(path, permission, identities, fetchOne)

    override def authorizeForEveryOr[E <: Throwable](path: AclAddress, permissions: Set[Permission])(onError: => E)(
        implicit caller: Caller
    ): IO[Unit]                            =
      path.ancestors
        .foldM((false, Set.empty[Permission])) {
          case ((true, set), _)        => IO.pure((true, set))
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

    override def mapFilterOrRaise[A, B](
        values: Iterable[A],
        extractAddressPermission: A => (AclAddress, Permission),
        onAuthorized: A => B,
        onFailure: AclAddress => IO[Unit]
    )(implicit caller: Caller): IO[Set[B]] = fetchAll.flatMap { allAcls =>
      values.toList.foldLeftM(Set.empty[B]) { case (acc, value) =>
        val (address, permission) = extractAddressPermission(value)
        authorizeFor(address, permission, allAcls).flatMap { success =>
          if (success)
            IO.pure(acc + onAuthorized(value))
          else
            onFailure(address) >> IO.pure(acc)
        }
      }
    }

    def mapFilterAtAddressOrRaise[A, B](
        values: Iterable[A],
        address: AclAddress,
        extractPermission: A => Permission,
        onAuthorized: A => B,
        onFailure: AclAddress => IO[Unit]
    )(implicit caller: Caller): IO[Set[B]] =
      Ref.of[IO, Map[AclAddress, Acl]](Map.empty).flatMap { cache =>
        def fetch: AclAddress => IO[Acl] = (address: AclAddress) =>
          cache.get.map(_.get(address)).flatMap {
            case Some(acl) => IO.pure(acl)
            case None      => fetchOne(address).flatTap { acl => cache.update { _.updated(address, acl) } }
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
  }

}
