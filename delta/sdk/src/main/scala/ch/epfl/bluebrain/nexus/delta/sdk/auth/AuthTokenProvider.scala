package ch.epfl.bluebrain.nexus.delta.sdk.auth

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.cache.LocalCache
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.{AuthToken, ParsedToken}
import ch.epfl.bluebrain.nexus.delta.sdk.auth.Credentials.ClientCredentials

import java.time.{Duration, Instant}

/**
  * Provides an auth token for the service account, for use when comunicating with remote Delta instances
  */
trait AuthTokenProvider {
  def apply(credentials: Credentials): IO[Option[AuthToken]]
}

object AuthTokenProvider {
  def apply(authService: OpenIdAuthService, clock: Clock[IO]): IO[AuthTokenProvider] = {
    LocalCache[ClientCredentials, ParsedToken]()
      .map(cache => new CachingOpenIdAuthTokenProvider(authService, cache, clock))
  }
  def anonymousForTest: AuthTokenProvider            = new AnonymousAuthTokenProvider
  def fixedForTest(token: String): AuthTokenProvider = new AuthTokenProvider {
    override def apply(credentials: Credentials): IO[Option[AuthToken]] = IO.pure(Some(AuthToken(token)))
  }
}

private class AnonymousAuthTokenProvider extends AuthTokenProvider {
  override def apply(credentials: Credentials): IO[Option[AuthToken]] = IO.pure(None)
}

/**
  * Uses the supplied credentials to get an auth token from an open id service. This token is cached until near-expiry
  * to speed up operations
  */
private class CachingOpenIdAuthTokenProvider(
    service: OpenIdAuthService,
    cache: LocalCache[ClientCredentials, ParsedToken],
    clock: Clock[IO]
) extends AuthTokenProvider {

  private val logger = Logger[CachingOpenIdAuthTokenProvider]

  override def apply(credentials: Credentials): IO[Option[AuthToken]] = {

    credentials match {
      case Credentials.Anonymous          => IO.pure(None)
      case Credentials.JWTToken(token)    => IO.pure(Some(AuthToken(token)))
      case credentials: ClientCredentials => clientCredentialsFlow(credentials)
    }
  }

  private def clientCredentialsFlow(credentials: ClientCredentials): IO[Some[AuthToken]] = {
    for {
      existingValue <- cache.get(credentials)
      now           <- clock.realTimeInstant
      finalValue    <- existingValue match {
                         case None                                 =>
                           logger.info("Fetching auth token, no initial value.") *>
                             fetchValue(credentials)
                         case Some(value) if isExpired(value, now) =>
                           logger.info("Fetching new auth token, current value near expiry.") *>
                             fetchValue(credentials)
                         case Some(value)                          => IO.pure(value)
                       }
    } yield {
      Some(AuthToken(finalValue.rawToken))
    }
  }

  private def fetchValue(credentials: ClientCredentials): IO[ParsedToken] = {
    cache.getOrElseUpdate(credentials, service.auth(credentials))
  }

  private def isExpired(value: ParsedToken, now: Instant): Boolean = {
    // minus 10 seconds to account for tranport / processing time
    val cutoffTime = value.expirationTime.minus(Duration.ofSeconds(10))

    now.isAfter(cutoffTime)
  }
}
