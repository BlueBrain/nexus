package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclsImpl.AclsLog
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore

final class AclsImpl private (
    log: AclsLog,
    minimum: Set[Permission]
) extends Acls {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

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
        val col = AclCollection(as: _*)
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

  override def replace(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource]         =
    eval(ReplaceAcl(acl, rev, caller)).span("replaceAcls")

  override def append(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource] =
    eval(AppendAcl(acl, rev, caller)).span("appendAcls")

  override def subtract(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource] =
    eval(SubtractAcl(acl, rev, caller)).span("subtractAcls")

  override def delete(address: AclAddress, rev: Int)(implicit caller: Subject): IO[AclResource] =
    eval(DeleteAcl(address, rev, caller)).span("deleteAcls")

  private def eval(cmd: AclCommand): IO[AclResource] = log.evaluate(cmd.address, cmd).map(_._2.toResource)
  override def purge(project: AclAddress): IO[Unit]  = log.delete(project)
}

object AclsImpl {

  type AclsLog = GlobalEventLog[AclAddress, AclState, AclCommand, AclEvent, AclRejection]

  def findUnknownRealms(xas: Transactors)(labels: Set[Label]): IO[Unit] = {
    GlobalStateStore
      .listIds(Realms.entityType, xas.read)
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
      config: AclsConfig,
      xas: Transactors,
      clock: Clock[IO]
  ): Acls =
    new AclsImpl(
      GlobalEventLog(Acls.definition(fetchPermissionSet, findUnknownRealms, clock), config.eventLog, xas),
      minimum
    )

}
