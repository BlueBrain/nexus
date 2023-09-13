package ch.epfl.bluebrain.nexus.delta.sdk.auth

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.MigrateEffectSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sdk.auth.Credentials.{Anonymous, ClientCredentials}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.ParsedToken
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
  def apply(credentials: Credentials, authService: OpenIdAuthService): AuthTokenProvider = {
    credentials match {
      case clientCredentials: ClientCredentials =>
        new CachingOpenIdAuthTokenProvider(clientCredentials, authService)
      case Credentials.JWTToken(jwtToken)       => new FixedAuthTokenProvider(AuthToken(jwtToken))
      case Anonymous                            => new AnonymousAuthTokenProvider
    }
  }
  def anonymousForTest: AuthTokenProvider = new AnonymousAuthTokenProvider
}

private class AnonymousAuthTokenProvider extends AuthTokenProvider {
  override def apply(): UIO[Option[AuthToken]] = UIO.pure(None)
}

/**
  * Uses a fixed (probably long-living) auth token. Should be removed when we are confident with the credentials method
  */
private class FixedAuthTokenProvider(authToken: AuthToken) extends AuthTokenProvider {
  override def apply(): UIO[Option[AuthToken]] = UIO.pure(Some(authToken))
}

/**
  * Uses the supplied credentials to get an auth token from an open id service. This token is cached until near-expiry
  * to speed up operations
  */
private class CachingOpenIdAuthTokenProvider(credentials: ClientCredentials, service: OpenIdAuthService)(implicit
    clock: Clock[UIO]
) extends AuthTokenProvider
    with MigrateEffectSyntax {

  private val logger = Logger.cats[CachingOpenIdAuthTokenProvider]
  private val cache  = KeyValueStore.create[Unit, ParsedToken]()

  override def apply(): UIO[Option[AuthToken]] = {
    for {
      existingValue <- cache.get(())
      now           <- IOUtils.instant
      finalValue    <- existingValue match {
                         case None                                 =>
                           logger.info("Fetching auth token, no initial value.").toUIO >>
                             fetchValue
                         case Some(value) if isExpired(value, now) =>
                           logger.info("Fetching new auth token, current value near expiry.").toUIO >>
                             fetchValue
                         case Some(value)                          => UIO.pure(value)
                       }
    } yield {
      Some(AuthToken(finalValue.rawToken))
    }
  }

  private def fetchValue = {
    cache.getOrElseUpdate((), service.auth(credentials))
  }

  private def isExpired(value: ParsedToken, now: Instant): Boolean = {
    // minus 10 seconds to account for tranport / processing time
    val cutoffTime = value.expirationTime.minus(Duration.ofSeconds(10))

    now.isAfter(cutoffTime)
  }
}
