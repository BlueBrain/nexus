package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageIsDeprecated
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

trait FetchStorage {

  /**
    * Attempts to fetch the storage in a read context and validates if the current user has access to it
    */
  def onRead(id: ResourceRef, project: ProjectRef)(implicit caller: Caller): IO[Storage]

  /**
    * Attempts to fetch the provided storage or the default one in a write context
    */
  def onWrite(id: Option[Iri], project: ProjectRef)(implicit
      caller: Caller
  ): IO[(ResourceRef.Revision, Storage)]
}

object FetchStorage {

  def apply(storages: Storages, aclCheck: AclCheck): FetchStorage = new FetchStorage {

    override def onRead(id: ResourceRef, project: ProjectRef)(implicit caller: Caller): IO[Storage] =
      storages.fetch(id, project).map(_.value).flatTap { storage =>
        validateAuth(project, storage.storageValue.readPermission)
      }

    override def onWrite(id: Option[Iri], project: ProjectRef)(implicit
        caller: Caller
    ): IO[(ResourceRef.Revision, Storage)] =
      for {
        storage <- id match {
                     case Some(id) => storages.fetch(Latest(id), project)
                     case None     => storages.fetchDefault(project)
                   }
        _       <- IO.raiseWhen(storage.deprecated)(StorageIsDeprecated(storage.id))
        _       <- validateAuth(project, storage.value.storageValue.writePermission)
      } yield ResourceRef.Revision(storage.id, storage.rev) -> storage.value

    private def validateAuth(project: ProjectRef, permission: Permission)(implicit c: Caller): IO[Unit] =
      aclCheck.authorizeForOr(project, permission)(AuthorizationFailed(project, permission))
  }

}
