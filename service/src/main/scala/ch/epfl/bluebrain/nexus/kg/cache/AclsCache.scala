package ch.epfl.bluebrain.nexus.kg.cache

import akka.actor.ActorSystem
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.types.events.Event._
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlList, AccessControlLists, ResourceAccessControlList}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path

/**
  * The acl cache backed by a KeyValueStore using akka Distributed Data
  *
  * @param store the underlying Distributed Data LWWMap store.
  */
class AclsCache[F[_]] private (store: KeyValueStore[F, Path, ResourceAccessControlList])(implicit F: Monad[F])
    extends Cache[F, Path, ResourceAccessControlList](store) {

  /**
    * Fetches all the ACLs
    */
  def list: F[AccessControlLists] =
    store.entries.map(AccessControlLists.apply)

  /**
    * Appends the provided ACLs to the existing ACLs on the provided ''path''.
    *
    * @param path the path
    * @param acl  the acls to append on the provided path
    */
  def append(path: Path, acl: ResourceAccessControlList): F[Unit] =
    get(path).flatMap {
      case Some(curr) =>
        replace(path, acl.copy(value = curr.value ++ acl.value, createdBy = curr.createdBy, createdAt = curr.createdAt))
      case _          =>
        replace(path, acl)
    }

  /**
    * Subtracts the provided ACLs from the existing ACLs on the provided ''path''.
    *
    * @param path the path
    * @param acl  the acls to subtract from the provided path
    */
  def subtract(path: Path, acl: ResourceAccessControlList): F[Unit] =
    get(path).flatMap {
      case Some(curr) =>
        replace(
          path,
          acl.copy(value = subtract(acl.value, curr.value), createdBy = curr.createdBy, createdAt = curr.createdAt)
        )
      case _          =>
        F.unit
    }

  /**
    * Deletes a path from the store.
    *
    * @param path the path to be deleted from the store
    */
  def remove(path: Path): F[Unit] = store.remove(path)

  private def subtract(acl: AccessControlList, curr: AccessControlList): AccessControlList =
    AccessControlList(acl.value.foldLeft(curr.value) {
      case (acc, (identity, permsToSubtract)) =>
        acc.get(identity).map(_ -- permsToSubtract) match {
          case Some(remaining) if remaining.isEmpty => acc - identity
          case Some(remaining)                      => acc + (identity -> remaining)
          case None                                 => acc
        }
    })
}

object AclsCache {

  private def toResourceAcl(event: AclEvent, acl: AccessControlList)(implicit
      iamConfig: IamConfig
  ): ResourceAccessControlList =
    ResourceAccessControlList(
      iamConfig.iamClient.aclsIri + event.path,
      event.rev,
      Set(nxv.AccessControlList.value),
      event.instant,
      event.subject,
      event.instant,
      event.subject,
      acl
    )

  /**
    * Creates a new acl index.
    */
  def apply[F[_]: Effect: Timer](
      iamClient: IamClient[F]
  )(implicit as: ActorSystem, config: AppConfig): AclsCache[F] = {
    val cache                       = new AclsCache(KeyValueStore.distributed("acls", (_, acls) => acls.rev))
    val handle: AclEvent => F[Unit] = {
      case event: AclReplaced   => cache.replace(event.path, toResourceAcl(event, event.acl))
      case event: AclAppended   => cache.append(event.path, toResourceAcl(event, event.acl))
      case event: AclSubtracted => cache.subtract(event.path, toResourceAcl(event, event.acl))
      case event: AclDeleted    => cache.remove(event.path)
    }
    iamClient.aclEvents(handle)(config.iam.serviceAccountToken)
    cache
  }
}
