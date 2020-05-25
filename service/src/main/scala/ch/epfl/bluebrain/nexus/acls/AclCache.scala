package ch.epfl.bluebrain.nexus.acls

import akka.actor.ActorSystem
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.acls.AclCache.RevisionedAccessControlList
import ch.epfl.bluebrain.nexus.cache.{Cache, KeyValueStore, KeyValueStoreConfig}

/**
  * The project cache backed by a KeyValueStore using akka Distributed Data
  *
  * @param store the underlying Distributed Data LWWMap store.
  */
class AclCache[F[_]] private[acls] (store: KeyValueStore[F, AclTarget, RevisionedAccessControlList])(
    implicit F: Monad[F]
) extends Cache[F, AclTarget, RevisionedAccessControlList](store) {

  /**
    * Attempts to fetch the ACL for the passed target.
    *
    * @param target           the target ACL
    * @param includeAncestors a flag to decide if the response should include the ACL ancestors or not.
    *                         E.g.: Ancestors of a target /myorg/myproject are:
    *                         /myorg
    *                         /
    */
  def get(target: AclTarget, includeAncestors: Boolean = false): F[AccessControlLists] =
    if (includeAncestors)
      store.entries.map(_.view.filterKeys(_.isAncestorOrEqualOf(target)).foldLeft(AccessControlLists.empty) {
        case (acc, (currTarget, (acl, _))) => acc + (currTarget -> acl)
      })
    else
      store.get(target).map(_.fold(AccessControlLists.empty)(revValue => AccessControlLists(target -> revValue._1)))

  /**
    * Replaces or creates the value on the cache with the passed ''id''
    */
  def replace(id: AclTarget, resource: Resource): F[Unit] =
    super.replace(id, (resource.value, resource.rev))

}

object AclCache {

  private[acls] type RevisionedAccessControlList = (AccessControlList, Long)

  /**
    * Creates a new ACL index.
    */
  final def apply[F[_]: Effect: Timer](implicit as: ActorSystem, config: KeyValueStoreConfig): AclCache[F] = {
    val store = KeyValueStore.distributed[F, AclTarget, RevisionedAccessControlList]("acls", (_, value) => value._2)
    new AclCache(store)
  }
}
