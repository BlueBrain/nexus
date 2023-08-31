package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import monix.bio.UIO

/**
  * Provides an auth token for the service account, for use when comunicating with remote storage
  */
trait AuthTokenProvider {
  def apply(): UIO[Option[AuthToken]]
}

object AuthTokenProvider {
  def apply(config: StorageTypeConfig): AuthTokenProvider = new AuthTokenProvider {
    override def apply(): UIO[Option[AuthToken]] =
      UIO.pure(config.remoteDisk.flatMap(_.defaultCredentials).map(secret => AuthToken(secret.value)))
  }
  def test(fixed: Option[AuthToken]): AuthTokenProvider   = new AuthTokenProvider {
    override def apply(): UIO[Option[AuthToken]] = UIO.pure(fixed)
  }

  def test(implicit config: StorageTypeConfig): AuthTokenProvider = {
    test(config.remoteDisk.flatMap(_.defaultCredentials).map(secret => AuthToken(secret.value)))
  }
}
