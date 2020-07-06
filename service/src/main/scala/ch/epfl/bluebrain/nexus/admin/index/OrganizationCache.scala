package ch.epfl.bluebrain.nexus.admin.index

import java.util.UUID

import akka.actor.ActorSystem
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.config.Permissions.orgs
import ch.epfl.bluebrain.nexus.admin.index.Cache._
import ch.epfl.bluebrain.nexus.admin.organizations.{Organization, OrganizationResource}
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStore.Subscription
import ch.epfl.bluebrain.nexus.commons.cache.{KeyValueStore, KeyValueStoreConfig, OnKeyValueStoreChange}
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.acls.AccessControlLists
import ch.epfl.bluebrain.nexus.service.config.AppConfig.HttpConfig

/**
  * The organization cache backed by a KeyValueStore using akka Distributed Data
  *
  * @param store the underlying Distributed Data LWWMap store.
  * @tparam F the effect type ''F[_]''
  */
class OrganizationCache[F[_]](store: KeyValueStore[F, UUID, OrganizationResource])(implicit
    F: Monad[F],
    http: HttpConfig
) extends Cache[F, Organization](store) {

  implicit private val ordering: Ordering[OrganizationResource] = Ordering.by { org: OrganizationResource =>
    org.value.label
  }

  /**
    * Return the elements on the store within the ''pagination'' bounds which are accessible by the provided acls with the permission 'projects/read'.
    *
    * @param params     the filter parameters
    * @param pagination the pagination
    */
  def list(
      params: SearchParams,
      pagination: FromPagination
  )(implicit acls: AccessControlLists): F[UnscoredQueryResults[OrganizationResource]] =
    store.values.map { values =>
      val filtered = values.filter {
        case ResourceF(_, _, rev, deprecated, types, _, createdBy, _, updatedBy, organization: Organization) =>
          params.organizationLabel.forall(_.matches(organization.label)) &&
            params.deprecated.forall(_ == deprecated) &&
            params.createdBy.forall(_ == createdBy.id) &&
            params.updatedBy.forall(_ == updatedBy.id) &&
            params.rev.forall(_ == rev) &&
            params.types.subsetOf(types) &&
            acls.exists(organization.label, orgs.read)
      }
      val count    = filtered.size.toLong
      val result   = filtered.toList.sorted.slice(pagination.from, pagination.from + pagination.size)
      UnscoredQueryResults(count, result.map(UnscoredQueryResult(_)))
    }

  /**
    * Attempts to fetch the organization resource with the provided ''label''
    *
    * @param label the organization label
    */
  def getBy(label: String): F[Option[OrganizationResource]] =
    store.findValue(_.value.label == label)

  /**
    * Subscribe to organization cache events and call certain functions
    *
   * @param onAdded      function to be called when an event has been added to the cache
    * @param onUpdated    function to be called when an event has been updated from the cache
    * @param onDeprecated function to be called when an event has been deleted from the cache
    */
  def subscribe(
      onAdded: OrganizationResource => F[Unit] = _ => F.unit,
      onUpdated: OrganizationResource => F[Unit] = _ => F.unit,
      onDeprecated: OrganizationResource => F[Unit] = _ => F.unit
  ): F[Subscription] =
    store.subscribe {
      OnKeyValueStoreChange(
        onCreate = (_, org) => onAdded(org),
        onUpdate = (_, org) => if (org.deprecated) onDeprecated(org) else onUpdated(org),
        onRemove = (_, _) => F.unit
      )
    }
}
object OrganizationCache {

  /**
    * Creates a new organization index.
    */
  def apply[F[_]: Effect: Timer](implicit
      as: ActorSystem,
      config: KeyValueStoreConfig,
      http: HttpConfig
  ): OrganizationCache[F] = {
    val function: (Long, OrganizationResource) => Long = { case (_, res) => res.rev }
    new OrganizationCache(KeyValueStore.distributed("organizations", function))
  }
}
