package ch.epfl.bluebrain.nexus.delta.sdk.migration

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import monix.bio.Task

trait RemoteStorageMigration {

  def run(newBaseUri: BaseUri): Task[Unit]

}
