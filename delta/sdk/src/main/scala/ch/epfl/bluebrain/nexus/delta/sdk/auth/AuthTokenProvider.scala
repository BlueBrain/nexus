package ch.epfl.bluebrain.nexus.delta.sdk.auth

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.MigrateEffectSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sdk.auth.Credentials.ClientCredentials
import ch.epfl.bluebrain.nexus.delta.sdk.identities.ParsedToken
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import monix.bio.UIO

import java.time.{Duration, Instant}

/**
  * Provides an auth token for the service account, for use when comunicating with remote storage
  */
trait AuthTokenProvider {
  def apply(credentials: Credentials): UIO[Option[AuthToken]]
}

object AuthTokenProvider {
  def apply(authService: OpenIdAuthService): UIO[AuthTokenProvider] = {
    KeyValueStore[ClientCredentials, ParsedToken]().map(cache => new CachingOpenIdAuthTokenProvider(authService, cache))
  }
  def anonymousForTest: AuthTokenProvider            = new AnonymousAuthTokenProvider
  def fixedForTest(token: String): AuthTokenProvider = new AuthTokenProvider {
    override def apply(credentials: Credentials): UIO[Option[AuthToken]] = UIO.pure(Some(AuthToken(token)))
  }
}

private class AnonymousAuthTokenProvider extends AuthTokenProvider {
  override def apply(credentials: Credentials): UIO[Option[AuthToken]] = UIO.pure(None)
}

/**
  * Uses the supplied credentials to get an auth token from an open id service. This token is cached until near-expiry
  * to speed up operations
  */
private class CachingOpenIdAuthTokenProvider(
    service: OpenIdAuthService,
    cache: KeyValueStore[ClientCredentials, ParsedToken]
)(implicit
    clock: Clock[UIO]
) extends AuthTokenProvider
    with MigrateEffectSyntax {

  private val logger = Logger.cats[CachingOpenIdAuthTokenProvider]

  override def apply(credentials: Credentials): UIO[Option[AuthToken]] = {

    credentials match {
      case Credentials.Anonymous          => UIO.pure(None)
      case Credentials.JWTToken(token)    => UIO.pure(Some(AuthToken(token)))
      case credentials: ClientCredentials => clientCredentialsFlow(credentials)
    }
  }

  private def clientCredentialsFlow(credentials: ClientCredentials) = {
    for {
      existingValue <- cache.get(credentials)
      now           <- IOUtils.instant
      finalValue    <- existingValue match {
                         case None                                 =>
                           logger.info("Fetching auth token, no initial value.").toUIO >>
                             fetchValue(credentials)
                         case Some(value) if isExpired(value, now) =>
                           logger.info("Fetching new auth token, current value near expiry.").toUIO >>
                             fetchValue(credentials)
                         case Some(value)                          => UIO.pure(value)
                       }
    } yield {
      Some(AuthToken(finalValue.rawToken))
    }
  }

  private def fetchValue(credentials: ClientCredentials) = {
    cache.getOrElseUpdate(credentials, service.auth(credentials))
  }

  private def isExpired(value: ParsedToken, now: Instant): Boolean = {
    // minus 10 seconds to account for tranport / processing time
    val cutoffTime = value.expirationTime.minus(Duration.ofSeconds(10))

    now.isAfter(cutoffTime)
  }
}
