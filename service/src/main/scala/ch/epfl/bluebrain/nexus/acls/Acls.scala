package ch.epfl.bluebrain.nexus.acls

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{NoOffset, PersistenceQuery}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.Monad
import cats.effect.{Clock, Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.ServiceError.UnexpectedInitialState
import ch.epfl.bluebrain.nexus.acls.AccessControlList.empty
import ch.epfl.bluebrain.nexus.acls.AclCommand._
import ch.epfl.bluebrain.nexus.acls.AclEvent._
import ch.epfl.bluebrain.nexus.acls.AclRejection._
import ch.epfl.bluebrain.nexus.acls.AclState.{Current, Initial}
import ch.epfl.bluebrain.nexus.acls.AclTarget.RootAcl
import ch.epfl.bluebrain.nexus.acls.Acls._
import ch.epfl.bluebrain.nexus.auth.Caller
import ch.epfl.bluebrain.nexus.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.config.AppConfig.{AclsConfig, HttpConfig, PermissionsConfig}
import ch.epfl.bluebrain.nexus.permissions.{Permission, Permissions}
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.{AggregateConfig, AkkaAggregate}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, StreamSupervisor}
import ch.epfl.bluebrain.nexus.{ServiceError, TaggingAdapter}
import retry.RetryPolicy

import scala.concurrent.ExecutionContext

//noinspection RedundantDefaultArgument
class Acls[F[_]](
    val agg: Agg[F],
    private[acls] val cache: AclCache[F]
)(implicit F: Effect[F], http: HttpConfig, pc: PermissionsConfig) {

  /**
    * Overrides ''acl'' on a ''target'' location.
    *
    * @param target the target location for the ACL
    * @param acl    the identity to permissions mapping to replace
    */
  def replace(target: AclTarget, rev: Long, acl: AccessControlList)(implicit caller: Caller): F[MetaOrRejection] =
    check(target, write) >> eval(target, ReplaceAcl(target, acl, rev, caller.subject)) <* updateIndex(target)

  /**
    * Appends ''acl'' on a ''target'' location.
    *
    * @param target the target location for the ACL
    * @param acl    the identity to permissions mapping to append
    */
  def append(target: AclTarget, rev: Long, acl: AccessControlList)(implicit caller: Caller): F[MetaOrRejection] =
    check(target, write) >> eval(target, AppendAcl(target, acl, rev, caller.subject)) <* updateIndex(target)

  /**
    * Subtracts ''acl'' on a ''target'' location.
    *
    * @param target the target location for the ACL
    * @param acl  the identity to permissions mapping to subtract
    */
  def subtract(target: AclTarget, rev: Long, acl: AccessControlList)(implicit caller: Caller): F[MetaOrRejection] =
    check(target, write) >> eval(target, SubtractAcl(target, acl, rev, caller.subject)) <* updateIndex(target)

  /**
    * Delete all ACL on a ''target'' location.
    *
    * @param target the target location for the ACL
    */
  def delete(target: AclTarget, rev: Long)(implicit caller: Caller): F[MetaOrRejection] =
    check(target, write) >> eval(target, DeleteAcl(target, rev, caller.subject)) <* updateIndex(target)

  private def eval(target: AclTarget, cmd: AclCommand): F[MetaOrRejection] =
    agg
      .evaluateS(target.toString, cmd)
      .flatMap {
        case Left(rej) => F.pure(Left(rej))
        case Right(Initial) =>
          F.raiseError(UnexpectedInitialState(http.aclsUri.copy(path = http.aclsUri.path ++ target.toPath)))
        case Right(c: Current) => F.pure(Right(c.resourceMetadata))
      }

  /**
    * Fetches the entire ACL for a ''targt'' location on the provided ''rev''.
    *
    * @param target the target location for the ACL
    * @param rev    the revision to fetch
    * @param self   flag to decide whether or not ACLs of other identities than the provided ones should be included in the response.
    *               This is constrained by the current caller having ''acls/read'' permissions on the provided ''path'' or it's parents
    */
  def fetch(target: AclTarget, rev: Long, self: Boolean)(implicit caller: Caller): F[ResourceOpt] =
    if (self) fetchUnsafe(target, rev).map(filterSelf)
    else check(target, read) >> fetchUnsafe(target, rev)

  /**
    * Fetches the entire ACL for a ''target'' location.
    *
    * @param target the target location for the ACL
    * @param self   flag to decide whether or not ACLs of other identities than the provided ones should be included in the response.
    *               This is constrained by the current caller having ''acls/read'' permissions on the provided ''path'' or it's parents
    */
  def fetch(target: AclTarget, self: Boolean)(implicit caller: Caller): F[ResourceOpt] =
    if (self) fetchUnsafe(target).map(filterSelf)
    else check(target, read) >> fetchUnsafe(target)

  /**
    * Fetches the [[AccessControlLists]] of the provided ''target'' location with some filtering options.
    *
    * @param target    the path where the ACLs are going to be looked up
    * @param ancestors flag to decide whether or not ancestor paths should be included in the response
    * @param self      flag to decide whether or not ancestor other identities than the provided ones should be included in the response
    * @param caller    the caller that contains the provided identities
    */
  def list(target: AclTarget, ancestors: Boolean, self: Boolean)(implicit caller: Caller): F[AccessControlLists] =
    if (self) cache.get(target, ancestors).map(_.filter(caller.identities))
    else cache.get(target, ancestors)

  private def fetchUnsafe(target: AclTarget): F[ResourceOpt] =
    agg.currentState(target.toString).map(stateToAcl(target, _))

  private def fetchUnsafe(target: AclTarget, rev: Long): F[ResourceOpt] =
    agg
      .foldLeft[AclState](target.toString, Initial) {
        case (state, event) if event.rev <= rev => next(state, event)
        case (state, _)                         => state
      }
      .map {
        case Initial if rev != 0L       => None
        case c: Current if rev != c.rev => None
        case other                      => stateToAcl(target, other)
      }

  private def stateToAcl(target: AclTarget, state: AclState): ResourceOpt =
    (state, target) match {
      case (Initial, RootAcl) => Some(defaultResourceOnRoot)
      case (Initial, _)       => None
      case (c: Current, _)    => Some(c.resource)
    }

  private def check(target: AclTarget, permission: Permission)(implicit caller: Caller): F[Unit] =
    hasPermission(target, permission, ancestors = true)
      .ifM(
        F.unit,
        F.raiseError(
          ServiceError.AccessDenied(http.aclsUri.copy(path = http.aclsUri.path ++ target.toPath), permission)
        )
      )

  private def filterSelf(opt: ResourceOpt)(implicit caller: Caller): ResourceOpt =
    opt.map(_.map(acl => acl.filter(caller.identities)))

  def hasPermission(target: AclTarget, perm: Permission, ancestors: Boolean = true)(
      implicit caller: Caller
  ): F[Boolean] = {
    fetchUnsafe(target).flatMap {
      case Some(res) if res.value.hasPermission(caller.identities, perm) => F.pure(true)
      case _ if target == RootAcl                                        => F.pure(false)
      case _ if ancestors                                                => hasPermission(target.parent, perm)
      case _                                                             => F.pure(false)
    }
  }

  private[acls] def updateIndex(target: AclTarget): F[Unit] = {
    fetchUnsafe(target).flatMap {
      case Some(res) => cache.replace(target, res) >> F.unit
      case None      => F.unit
    }
  }

}

object Acls {

  /**
    * Constructs a new acls aggregate.
    */
  def aggregate[F[_]: Effect: Timer: Clock](
      perms: F[Permissions[F]]
  )(implicit as: ActorSystem, ac: AclsConfig): F[Agg[F]] = {
    implicit val retryPolicy: RetryPolicy[F] = ac.aggregate.retry.retryPolicy[F]
    AkkaAggregate
      .sharded[F]
      .apply(
        "acls",
        AclState.Initial,
        next,
        evaluate[F](perms),
        ac.aggregate.passivationStrategy(),
        ac.aggregate.akkaAggregateConfig,
        ac.aggregate.shards
      )
  }

  /**
    * Constructs an ACL cache.
    */
  def cache[F[_]: Effect: Timer](implicit as: ActorSystem, aclcfg: AclsConfig): AclCache[F] = {
    implicit val kvcfg: KeyValueStoreConfig = aclcfg.keyValueStore
    AclCache[F]
  }

  /**
    * Constructs a new ACLs api using the provided aggregate, a lazy reference to the permissions api and an index.
    *
    * @param agg   the acl aggregate
    * @param cache an acl cache
    */
  def apply[F[_]: Effect](
      agg: Agg[F],
      cache: AclCache[F]
  )(implicit http: HttpConfig, pc: PermissionsConfig): Acls[F] =
    new Acls(agg, cache)

  /**
    * Constructs a new ACLs api using the provided aggregate, a lazy reference to the permissions api and an index.
    *
    * @param perms a lazy reference to the permissions api
    */
  def apply[F[_]: Effect: Timer: Clock](perms: F[Permissions[F]])(
      implicit
      as: ActorSystem,
      http: HttpConfig,
      ac: AclsConfig,
      pc: PermissionsConfig
  ): F[Acls[F]] =
    delay(aggregate(perms), cache)

  /**
    * Constructs a new ACLs api using the provided aggregate, a lazy reference to the permissions api and an index.
    *
    * @param agg   the acl aggregate
    * @param cache an acl cache
    */
  def delay[F[_]: Effect](
      agg: F[Agg[F]],
      cache: AclCache[F]
  )(implicit http: HttpConfig, pc: PermissionsConfig): F[Acls[F]] =
    agg.map(apply(_, cache))

  /**
    * Builds a process for automatically updating the acl index with the latest events logged.
    *
    * @param acls the acls API
    */
  def indexer[F[_]: Timer](acls: Acls[F])(implicit F: Effect[F], as: ActorSystem, ac: AclsConfig): F[Unit] = {
    implicit val aggc: AggregateConfig = ac.aggregate
    implicit val ec: ExecutionContext  = as.dispatcher
    implicit val timeout: Timeout      = aggc.askTimeout

    val projectionId: String = "acl-index"
    val source: Source[PairMsg[Any], _] = PersistenceQuery(as)
      .readJournalFor[EventsByTagQuery](ac.aggregate.queryJournalPlugin)
      .eventsByTag(TaggingAdapter.aclEventTag, NoOffset)
      .map[PairMsg[Any]](e => Right(Message(e, projectionId)))

    val flow = ProgressFlowElem[F, Any]
      .collectCast[Event]
      .mapAsync(ev => acls.fetchUnsafe(ev.target).map(_.map(ev.target -> _)))
      .collectSome[(AclTarget, Resource)]
      .mapAsync { case (target, aclResource) => acls.cache.replace(target, aclResource) }
      .flow
      .map(_ => ())

    F.delay[StreamSupervisor[F, Unit]](StreamSupervisor.startSingleton(F.delay(source.via(flow)), projectionId)) >> F.unit

  }

  private[acls] def next(state: State, event: Event): AclState = {
    def replaced(e: AclReplaced): State = state match {
      case Initial    => Current(e.target, e.acl, 1L, e.instant, e.instant, e.subject, e.subject)
      case c: Current => c.copy(acl = e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
    }
    def appended(e: AclAppended): State = state match {
      case Initial    => Current(e.target, e.acl, 1L, e.instant, e.instant, e.subject, e.subject)
      case c: Current => c.copy(acl = c.acl ++ e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
    }
    def subtracted(e: AclSubtracted): State = state match {
      case Initial    => Current(e.target, e.acl, 1L, e.instant, e.instant, e.subject, e.subject)
      case c: Current => c.copy(acl = c.acl -- e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
    }
    def deleted(e: AclDeleted): State = state match {
      case Initial    => Initial
      case c: Current => c.copy(acl = empty, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
    }
    event match {
      case ev: AclReplaced   => replaced(ev)
      case ev: AclAppended   => appended(ev)
      case ev: AclSubtracted => subtracted(ev)
      case ev: AclDeleted    => deleted(ev)
    }
  }

  private def evaluate[F[_]: Monad: Clock](
      perms: F[Permissions[F]]
  )(state: State, cmd: Command): F[EventOrRejection] = {
    val F = implicitly[Monad[F]]
    val C = implicitly[Clock[F]]
    def accept(f: Instant => Event): F[EventOrRejection] =
      C.realTime(TimeUnit.MILLISECONDS).map(rtl => Right(f(Instant.ofEpochMilli(rtl))))
    def reject(rejection: Rejection): F[EventOrRejection] =
      F.pure(Left(rejection))
    def acceptChecking(acl: AccessControlList)(f: Instant => Event): F[EventOrRejection] =
      perms.flatMap(_.effectivePermissionsUnsafe.flatMap {
        case ps if acl.permissions.subsetOf(ps) => accept(f)
        case ps                                 => reject(UnknownPermissions(acl.permissions -- ps))
      })

    def replaced(c: ReplaceAcl): F[EventOrRejection] = state match {
      case _ if c.acl.hasVoidPermissions => reject(AclCannotContainEmptyPermissionCollection(c.target))
      case Initial if c.rev == 0L        => acceptChecking(c.acl)(AclReplaced(c.target, c.acl, 1L, _, c.subject))
      case Initial                       => reject(IncorrectRev(c.target, c.rev, 0L))
      case ss: Current if ss.acl.permissions.nonEmpty && c.rev != ss.rev =>
        reject(IncorrectRev(c.target, c.rev, ss.rev))
      case ss: Current if ss.acl.permissions.isEmpty && c.rev != ss.rev && c.rev != 0L =>
        reject(IncorrectRev(c.target, c.rev, ss.rev))
      case ss: Current => acceptChecking(c.acl)(AclReplaced(c.target, c.acl, ss.rev + 1, _, c.subject))
    }
    def append(c: AppendAcl): F[EventOrRejection] = state match {
      case Initial if c.rev == 0L                                     => acceptChecking(c.acl)(AclAppended(c.target, c.acl, c.rev + 1, _, c.subject))
      case Initial                                                    => reject(IncorrectRev(c.target, c.rev, 0L))
      case s: Current if s.acl.permissions.nonEmpty && c.rev != s.rev => reject(IncorrectRev(c.target, c.rev, s.rev))
      case s: Current if s.acl.permissions.isEmpty && c.rev != s.rev & c.rev != 0L =>
        reject(IncorrectRev(c.target, c.rev, s.rev))
      case _: Current if c.acl.hasVoidPermissions => reject(AclCannotContainEmptyPermissionCollection(c.target))
      case s: Current if s.acl ++ c.acl == s.acl  => reject(NothingToBeUpdated(c.target))
      case s: Current                             => acceptChecking(c.acl)(AclAppended(c.target, c.acl, s.rev + 1, _, c.subject))
    }
    def subtract(c: SubtractAcl): F[EventOrRejection] = state match {
      case Initial                                => reject(AclNotFound(c.target))
      case s: Current if c.rev != s.rev           => reject(IncorrectRev(c.target, c.rev, s.rev))
      case _: Current if c.acl.hasVoidPermissions => reject(AclCannotContainEmptyPermissionCollection(c.target))
      case s: Current if s.acl -- c.acl == s.acl  => reject(NothingToBeUpdated(c.target))
      case _: Current                             => acceptChecking(c.acl)(AclSubtracted(c.target, c.acl, c.rev + 1, _, c.subject))
    }
    def delete(c: DeleteAcl): F[EventOrRejection] = state match {
      case Initial                      => reject(AclNotFound(c.target))
      case s: Current if c.rev != s.rev => reject(IncorrectRev(c.target, c.rev, s.rev))
      case s: Current if s.acl == empty => reject(AclIsEmpty(c.target))
      case _: Current                   => accept(AclDeleted(c.target, c.rev + 1, _, c.subject))
    }

    cmd match {
      case c: ReplaceAcl  => replaced(c)
      case c: AppendAcl   => append(c)
      case c: SubtractAcl => subtract(c)
      case c: DeleteAcl   => delete(c)
    }
  }
}
