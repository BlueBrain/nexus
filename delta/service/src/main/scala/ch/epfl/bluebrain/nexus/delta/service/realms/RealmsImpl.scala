package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.DeprecateRealm
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{RealmCommand, RealmEvent, RealmRejection, RealmState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.{RealmResource, Realms}
import ch.epfl.bluebrain.nexus.delta.service.permissions.PermissionsImpl.entityId
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsImpl.RealmsAggregate
import ch.epfl.bluebrain.nexus.sourcing.Aggregate
import monix.bio.{IO, UIO}

class RealmsImpl(agg: RealmsAggregate) extends Realms {

  override def create(label: Label,
                      name: Name,
                      openIdConfig: Uri,
                      logo: Option[Uri])(implicit caller: Identity.Subject): IO[RealmRejection, RealmResource] = ???

  override def update(label: Label, rev: Long, name: Name, openIdConfig: Uri, logo: Option[Uri])(implicit caller: Identity.Subject): IO[RealmRejection, RealmResource] = ???

  override def deprecate(label: Label, rev: Long)(implicit caller: Identity.Subject): IO[RealmRejection, RealmResource] =
    eval(DeprecateRealm(label, rev, caller))

  private def eval(cmd: RealmCommand): IO[RealmRejection, RealmResource] =
    agg.evaluate(cmd.label.value, cmd).mapError(_.value).flatMap { success =>
      IO.fromOption(
        success.state.toResource,
        UnexpectedInitialState(cmd.label)
      )
    }

  override def fetch(label: Label): UIO[Option[RealmResource]] =
    agg.state(label.value).map(_.toResource).hideErrors

  override def fetchAt(label: Label, rev: Long): IO[RealmRejection.RevisionNotFound, Option[RealmResource]] =
    if (rev == 0L) UIO.pure(RealmState.Initial.toResource)
    else
      agg
        .events(entityId)
        .takeWhile(_.rev <= rev)
        .fold[RealmState](RealmState.Initial) {
          case (state, event) => Realms.next(state, event)
        }
        .compile
        .last
        .hideErrors
        .flatMap {
          case Some(state) if state.rev == rev => UIO.pure(state.toResource)
          case Some(_)                         => fetch(label).flatMap { res =>
                                                    IO.raiseError(
                                                      RevisionNotFound(
                                                        rev,
                                                        res.fold(0L)(_.rev))
                                                      )
                                                  }
          case None                            => IO.raiseError(RevisionNotFound(rev, 0L))
        }

  override def list(pagination: Pagination.FromPagination, params: SearchParams.RealmSearchParams): UIO[SearchResults.UnscoredSearchResults[RealmResource]] = ???
}

object RealmsImpl {

  type RealmsAggregate = Aggregate[String, RealmState, RealmCommand, RealmEvent, RealmRejection]

  /**
   * The realms entity type.
   */
  final val entityType: String = "realms"



}
