package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

trait FetchFileStorage {
  def fetchAndValidateActiveStorage(storageIdOpt: Option[IdSegment], ref: ProjectRef, pc: ProjectContext)(implicit
      caller: Caller
  ): IO[(ResourceRef.Revision, Storage)]
}
