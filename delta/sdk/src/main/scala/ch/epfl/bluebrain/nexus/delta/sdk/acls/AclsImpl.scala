package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.std.Env
import cats.effect.{Clock, IO}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclsImpl.AclsLog
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.*
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore
import doobie.syntax.all.*
import fs2.Stream

final class AclsImpl private (
    log: AclsLog,
    minimum: Set[Permission]
) extends Acls {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def isRootAclSet: IO[Boolean] =
    log
      .stateOr(AclAddress.Root, AclNotFound(AclAddress.Root))
      .redeem(
        _ => false,
        _ => true
      )

  override def fetch(address: AclAddress): IO[AclResource] =
    log
      .stateOr(address, AclNotFound(address))
      .recover {
        case AclNotFound(a) if a == AclAddress.Root => AclState.initial(minimum)
      }
      .map(_.toResource)
      .span("fetchAcl")

  override def fetchWithAncestors(address: AclAddress): IO[AclCollection] =
    super.fetchWithAncestors(address).span("fetchWithAncestors")

  override def fetchAt(address: AclAddress, rev: Int): IO[AclResource] =
    log
      .stateOr(address, rev, AclNotFound(address), RevisionNotFound)
      .recover {
        case AclNotFound(a) if a == AclAddress.Root && rev == 0 => AclState.initial(minimum)
      }
      .map(_.toResource)
      .span("fetchAclAt")

  override def list(filter: AclAddressFilter): IO[AclCollection] = {
    log
      .currentStates(_.toResource)
      .filter { a =>
        filter.matches(a.value.address)
      }
      .compile
      .toList
      .map { as =>
        val col = AclCollection(as*)
        col.value.get(AclAddress.Root) match {
          case None if filter.withAncestors => col + AclState.initial(minimum).toResource
          case _                            => col
        }
      }
      .span("listAcls", Map("withAncestors" -> filter.withAncestors))
  }

  override def listSelf(filter: AclAddressFilter)(implicit caller: Caller): IO[AclCollection] =
    list(filter)
      .map(_.filter(caller.identities))
      .span("listSelfAcls", Map("withAncestors" -> filter.withAncestors))

  override def states(offset: Offset): Stream[IO, AclState] = log.currentStates(offset, identity)

  override def replace(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource] =
    eval(ReplaceAcl(acl, rev, caller)).span("replaceAcls")

  override def append(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource] =
    eval(AppendAcl(acl, rev, caller)).span("appendAcls")

  override def subtract(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource] =
    eval(SubtractAcl(acl, rev, caller)).span("subtractAcls")

  override def delete(address: AclAddress, rev: Int)(implicit caller: Subject): IO[AclResource] =
    eval(DeleteAcl(address, rev, caller)).span("deleteAcls")

  private def eval(cmd: AclCommand): IO[AclResource] = log.evaluate(cmd.address, cmd).map(_._2.toResource)

  override def purge(acl: AclAddress): IO[Unit] = log.delete(acl)
}

object AclsImpl {

  type AclsLog = GlobalEventLog[AclAddress, AclState, AclCommand, AclEvent, AclRejection]

  private val logger = Logger[AclsImpl]

  def findUnknownRealms(xas: Transactors)(labels: Set[Label]): IO[Unit] = {
    GlobalStateStore
      .listIds(Realms.entityType, xas.write)
      .compile
      .toList
      .flatMap { existing =>
        val unknown = labels.filterNot { l =>
          existing.contains(Realms.encodeId(l))
        }
        IO.raiseWhen(unknown.nonEmpty)(UnknownRealms(unknown))
      }
  }

  /**
    * Constructs an [[AclsImpl]] instance.
    */
  final def apply(
      fetchPermissionSet: IO[Set[Permission]],
      findUnknownRealms: Set[Label] => IO[Unit],
      minimum: Set[Permission],
      config: EventLogConfig,
      flattenedAclStore: FlattenedAclStore,
      xas: Transactors,
      clock: Clock[IO]
  ): Acls =
    new AclsImpl(
      GlobalEventLog(Acls.definition(fetchPermissionSet, findUnknownRealms, flattenedAclStore, clock), config, xas),
      minimum
    )

  final def applyWithInitial(
      fetchPermissionSet: IO[Set[Permission]],
      findUnknownRealms: Set[Label] => IO[Unit],
      minimum: Set[Permission],
      config: EventLogConfig,
      flattenedAclStore: FlattenedAclStore,
      xas: Transactors,
      clock: Clock[IO]
  ): IO[Acls] = {
    val acls = apply(fetchPermissionSet, findUnknownRealms, minimum, config, flattenedAclStore, xas, clock)
    for {
      shouldReplay <- Env[IO].get("RESET_ACL_PROJECTION").map(_.getOrElse("false").toBoolean)
      _            <- IO.whenA(shouldReplay)(replayAclProjection(acls, flattenedAclStore, xas))
      isRootAclSet <- acls.isRootAclSet
      _            <- IO.unlessA(isRootAclSet) {
                        val initial = AclState.initial(minimum).acl
                        (flattenedAclStore.delete(AclAddress.Root) >> flattenedAclStore.insert(AclAddress.Root, initial.value))
                          .transact(xas.write)
                      }
      _            <- IO.unlessA(isRootAclSet) { logger.warn("No root acl are set, please define some in real deployments.") }
    } yield acls
  }

  private[acls] def replayAclProjection(acls: Acls, flattenedAclStore: FlattenedAclStore, xas: Transactors) =
    logger.warn("Replay acl projection as the env RESET_ACL_PROJECTION is set...") >>
      flattenedAclStore.reset.transact(xas.write) >>
      acls
        .states(Offset.Start)
        .evalTap { state =>
          flattenedAclStore.insert(state.acl.address, state.acl.value).transact(xas.write)
        }
        .compile
        .drain >>
      logger.info("Acl projection has been successfully replayed.")
}
