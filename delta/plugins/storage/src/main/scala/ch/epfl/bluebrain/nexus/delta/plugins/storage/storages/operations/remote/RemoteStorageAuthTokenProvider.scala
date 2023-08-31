package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import monix.bio.UIO

trait RemoteStorageAuthTokenProvider {
  def apply(): UIO[Option[AuthToken]]
}

object RemoteStorageAuthTokenProvider {
  def apply(config: StorageTypeConfig): RemoteStorageAuthTokenProvider = new RemoteStorageAuthTokenProvider {
    override def apply(): UIO[Option[AuthToken]] =
      UIO.pure(config.remoteDisk.flatMap(_.defaultCredentials).map(secret => AuthToken(secret.value)))
  }
  def test(fixed: Option[AuthToken]): RemoteStorageAuthTokenProvider   = new RemoteStorageAuthTokenProvider {
    override def apply(): UIO[Option[AuthToken]] = UIO.pure(fixed)
  }

  def test(implicit config: StorageTypeConfig): RemoteStorageAuthTokenProvider = {
    test(config.remoteDisk.flatMap(_.defaultCredentials).map(secret => AuthToken(secret.value)))
  }
}
