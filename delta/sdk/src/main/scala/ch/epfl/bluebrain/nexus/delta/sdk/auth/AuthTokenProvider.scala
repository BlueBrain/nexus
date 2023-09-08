package ch.epfl.bluebrain.nexus.delta.sdk.auth

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import monix.bio.UIO

import java.time.{Duration, Instant}

/**
  * Provides an auth token for the service account, for use when comunicating with remote storage
  */
trait AuthTokenProvider {
  def apply(): UIO[Option[AuthToken]]
}

object AuthTokenProvider {
  def apply(auth: Option[Credentials], keycloakAuthService: KeycloakAuthService): AuthTokenProvider = {
    auth match {
      case Some(authAs) => new CachingKeycloakAuthTokenProvider(authAs, keycloakAuthService)
      case None         => new AnonymousAuthTokenProvider
    }
  }
  def anonymousForTest: AuthTokenProvider = new AnonymousAuthTokenProvider
}

private class AnonymousAuthTokenProvider extends AuthTokenProvider {
  override def apply(): UIO[Option[AuthToken]] = UIO.pure(None)
}

private class CachingKeycloakAuthTokenProvider(identity: Credentials, service: KeycloakAuthService)(implicit
    clock: Clock[UIO]
) extends AuthTokenProvider {
  private val cache = KeyValueStore.create[Unit, AccessTokenWithMetadata]()

  override def apply(): UIO[Option[AuthToken]] = {
    for {
      existingValue <- cache.get(())
      now           <- IOUtils.instant
      finalValue    <- existingValue match {
                         case None                                 => fetchValue
                         case Some(value) if isExpired(value, now) => fetchValue
                         case Some(value)                          => UIO.pure(value)
                       }
    } yield {
      Some(AuthToken(finalValue.token))
    }
  }

  private def fetchValue = {
    cache.getOrElseUpdate((), service.auth(identity))
  }

  private def isExpired(value: AccessTokenWithMetadata, now: Instant): Boolean = {
    // minus 10 seconds to account for tranport / processing time
    val cutoffTime = value.expiresAt.minus(Duration.ofSeconds(10))

    now.isAfter(cutoffTime)
  }
}
