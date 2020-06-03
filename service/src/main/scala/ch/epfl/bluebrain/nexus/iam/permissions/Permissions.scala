package ch.epfl.bluebrain.nexus.iam.permissions

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import cats.Monad
import cats.effect.{Clock, Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.{HttpConfig, PermissionsConfig}
import ch.epfl.bluebrain.nexus.iam.permissions.Permissions._
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsState.{Current, Initial}
import ch.epfl.bluebrain.nexus.iam.types.IamError.{AccessDenied, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.iam.types._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AkkaAggregate
import retry.RetryPolicy

/**
  * Permissions API.
  *
  * @param agg  the permissions aggregate
  * @param acls a lazy acls api
  * @param http the application http configuration
  * @tparam F   the effect type
  */
class Permissions[F[_]](
    val agg: Agg[F],
    acls: F[Acls[F]]
)(implicit F: Effect[F], http: HttpConfig, pc: PermissionsConfig) {

  /**
    * The persistence id of the permissions singleton.
    */
  val persistenceId: String = "permissions"

  /**
    * @return the minimum set of permissions
    */
  def minimum: Set[Permission] =
    pc.minimum

  /**
    * @return the current permissions as a resource
    */
  def fetch(implicit caller: Caller): F[Resource] =
    check(read) >> fetchUnsafe

  /**
    * @param rev the permissions revision
    * @return the permissions as a resource at the specified revision
    */
  def fetchAt(rev: Long)(implicit caller: Caller): F[OptResource] =
    check(read) >> agg
      .foldLeft[State](persistenceId, Initial) {
        case (state, event) if event.rev <= rev => next(pc)(state, event)
        case (state, _)                         => state
      }
      .map {
        case Initial if rev != 0L       => None
        case c: Current if rev != c.rev => None
        case other                      => Some(other.resource)
      }

  /**
    * @return the current permissions collection
    */
  def effectivePermissions(implicit caller: Caller): F[Set[Permission]] =
    fetch.map(_.value)

  /**
    * @return the current permissions as a resource without checking permissions
    */
  def fetchUnsafe: F[Resource] =
    agg.currentState(persistenceId).map(_.resource)

  /**
    * @return the current permissions collection without checking permissions
    */
  def effectivePermissionsUnsafe: F[Set[Permission]] =
    fetchUnsafe.map(_.value)

  /**
    * Replaces the current collection of permissions with the provided collection.
    *
    * @param permissions the permissions to set
    * @param rev         the last known revision of the resource
    * @return the new resource metadata or a description of why the change was rejected
    */
  def replace(permissions: Set[Permission], rev: Long = 0L)(implicit caller: Caller): F[MetaOrRejection] =
    check(write) >> eval(ReplacePermissions(rev, permissions, caller.subject))

  /**
    * Appends the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to append
    * @param rev         the last known revision of the resource
    * @return the new resource metadata or a description of why the change was rejected
    */
  def append(permissions: Set[Permission], rev: Long = 0L)(implicit caller: Caller): F[MetaOrRejection] =
    check(write) >> eval(AppendPermissions(rev, permissions, caller.subject))

  /**
    * Subtracts the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to subtract
    * @param rev         the last known revision of the resource
    * @return the new resource metadata or a description of why the change was rejected
    */
  def subtract(permissions: Set[Permission], rev: Long)(implicit caller: Caller): F[MetaOrRejection] =
    check(write) >> eval(SubtractPermissions(rev, permissions, caller.subject))

  /**
    * Removes all but the minimum permissions from the collection of permissions.
    *
    * @param rev the last known revision of the resource
    * @return the new resource metadata or a description of why the change was rejected
    */
  def delete(rev: Long)(implicit caller: Caller): F[MetaOrRejection] =
    check(write) >> eval(DeletePermissions(rev, caller.subject))

  private def eval(cmd: Command): F[MetaOrRejection] =
    agg
      .evaluateS(persistenceId, cmd)
      .flatMap {
        case Left(rej) => F.pure(Left(rej))
        // $COVERAGE-OFF$
        case Right(Initial) => F.raiseError(UnexpectedInitialState(id))
        // $COVERAGE-ON$
        case Right(c: Current) => F.pure(Right(c.resourceMetadata))
      }

  private def check(permission: Permission)(implicit caller: Caller): F[Unit] =
    acls
      .flatMap(_.hasPermission(Path./, permission, ancestors = false))
      .ifM(F.unit, F.raiseError(AccessDenied(id, permission)))
}

object Permissions {

  /**
    * Constructs a new permissions aggregate.
    */
  def aggregate[F[_]: Effect: Timer](
      implicit as: ActorSystem,
      pc: PermissionsConfig
  ): F[Agg[F]] = {
    implicit val retryPolicy: RetryPolicy[F] = pc.aggregate.retry.retryPolicy[F]
    AkkaAggregate.sharded[F](
      "permissions",
      PermissionsState.Initial,
      next(pc),
      evaluate[F](pc),
      pc.aggregate.passivationStrategy(),
      pc.aggregate.akkaAggregateConfig,
      pc.aggregate.shards
    )
  }

  /**
    * Creates a new permissions api using the provided aggregate and a lazy reference to the ACL api.
    *
    * @param agg  the permissions aggregate
    * @param acls a lazy reference to the ACL api
    */
  def apply[F[_]: Effect](agg: Agg[F], acls: F[Acls[F]])(
      implicit
      http: HttpConfig,
      pc: PermissionsConfig
  ): Permissions[F] =
    new Permissions(agg, acls)

  /**
    * Creates a new permissions api using the default aggregate and a lazy reference to the ACL api.
    *
    * @param acls a lazy reference to the ACL api
    */
  def apply[F[_]: Effect: Timer](acls: F[Acls[F]])(
      implicit
      as: ActorSystem,
      http: HttpConfig,
      pc: PermissionsConfig
  ): F[Permissions[F]] =
    delay(aggregate, acls)

  /**
    * Creates a new permissions api using the provided aggregate and a lazy reference to the ACL api.
    *
    * @param agg  a lazy reference to the permissions aggregate
    * @param acls a lazy reference to the ACL api
    */
  def delay[F[_]: Effect](agg: F[Agg[F]], acls: F[Acls[F]])(
      implicit
      http: HttpConfig,
      pc: PermissionsConfig
  ): F[Permissions[F]] =
    agg.map(apply(_, acls))

  private[permissions] def next(pc: PermissionsConfig)(state: State, event: Event): State = {
    implicit val p: PermissionsConfig = pc
    def appended(e: PermissionsAppended): State = state match {
      case s: Initial if e.rev == 1L        => s.withPermissions(e.permissions, e.instant, e.subject)
      case s: Current if s.rev + 1 == e.rev => s.withPermissions(s.permissions ++ e.permissions, e.instant, e.subject)
      case other                            => other
    }
    def replaced(e: PermissionsReplaced): State = state match {
      case s if s.rev + 1 == e.rev => s.withPermissions(e.permissions, e.instant, e.subject)
      case other                   => other
    }
    def subtracted(e: PermissionsSubtracted): State = state match {
      case s: Current if s.rev + 1 == e.rev => s.withPermissions(s.permissions -- e.permissions, e.instant, e.subject)
      case other                            => other
    }
    def deleted(e: PermissionsDeleted): State = state match {
      case s: Current if s.rev + 1 == e.rev => s.withPermissions(Set.empty, e.instant, e.subject)
      case other                            => other
    }
    event match {
      case e: PermissionsAppended   => appended(e)
      case e: PermissionsReplaced   => replaced(e)
      case e: PermissionsSubtracted => subtracted(e)
      case e: PermissionsDeleted    => deleted(e)
    }
  }

  private def evaluate[F[_]: Monad: Clock](pc: PermissionsConfig)(state: State, cmd: Command): F[EventOrRejection] = {
    val F = implicitly[Monad[F]]
    val C = implicitly[Clock[F]]
    def accept(f: Instant => PermissionsEvent): F[EventOrRejection] =
      C.realTime(TimeUnit.MILLISECONDS).map(rtl => Right(f(Instant.ofEpochMilli(rtl))))
    def reject(rejection: PermissionsRejection): F[EventOrRejection] =
      F.pure(Left(rejection))

    def replace(c: ReplacePermissions): F[EventOrRejection] =
      if (c.rev != state.rev) reject(IncorrectRev(c.rev, state.rev))
      else if (c.permissions.isEmpty) reject(CannotReplaceWithEmptyCollection)
      else if ((c.permissions -- pc.minimum).isEmpty) reject(CannotReplaceWithEmptyCollection)
      else accept(PermissionsReplaced(c.rev + 1, c.permissions, _, c.subject))
    def append(c: AppendPermissions): F[EventOrRejection] = state match {
      case _ if state.rev != c.rev    => reject(IncorrectRev(c.rev, state.rev))
      case _ if c.permissions.isEmpty => reject(CannotAppendEmptyCollection)
      case Initial                    => accept(PermissionsAppended(1L, c.permissions, _, c.subject))
      case s: Current =>
        val appended = c.permissions -- s.permissions
        if (appended.isEmpty) reject(CannotAppendEmptyCollection)
        else accept(PermissionsAppended(c.rev + 1, c.permissions, _, c.subject))
    }
    def subtract(c: SubtractPermissions): F[EventOrRejection] = state match {
      case _ if state.rev != c.rev    => reject(IncorrectRev(c.rev, state.rev))
      case _ if c.permissions.isEmpty => reject(CannotSubtractEmptyCollection)
      case Initial                    => reject(CannotSubtractFromMinimumCollection(pc.minimum))
      case s: Current =>
        val intendedDelta = c.permissions -- s.permissions
        val delta         = c.permissions & s.permissions
        val subtracted    = delta -- pc.minimum
        if (intendedDelta.nonEmpty) reject(CannotSubtractUndefinedPermissions(intendedDelta))
        else if (subtracted.isEmpty) reject(CannotSubtractFromMinimumCollection(pc.minimum))
        else accept(PermissionsSubtracted(c.rev + 1, delta, _, c.subject))
    }
    def delete(c: DeletePermissions): F[EventOrRejection] = state match {
      case _ if state.rev != c.rev                   => reject(IncorrectRev(c.rev, state.rev))
      case Initial                                   => reject(CannotDeleteMinimumCollection)
      case s: Current if s.permissions == pc.minimum => reject(CannotDeleteMinimumCollection)
      case _: Current                                => accept(PermissionsDeleted(c.rev + 1, _, c.subject))
    }

    cmd match {
      case c: ReplacePermissions  => replace(c)
      case c: AppendPermissions   => append(c)
      case c: SubtractPermissions => subtract(c)
      case c: DeletePermissions   => delete(c)
    }
  }
}
