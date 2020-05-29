package ch.epfl.bluebrain.nexus.commons.es.client

import akka.http.scaladsl.model.StatusCodes.GatewayTimeout
import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchBaseClient._
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure.{ElasticServerError, ElasticUnexpectedError}
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import com.typesafe.scalalogging.Logger
import retry.CatsEffect._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

import scala.util.control.NonFatal

/**
  * Common methods and vals used for elastic clients.
  *
  * @tparam F the monadic effect type
  */
abstract class ElasticSearchBaseClient[F[_]: Timer](
    implicit retryConfig: RetryStrategyConfig,
    cl: UntypedHttpClient[F],
    F: Effect[F]
) {

  private[client] val log = Logger[this.type]

  private[client] implicit val retryPolicy: RetryPolicy[F] = retryConfig.retryPolicy[F]
  private[client] implicit val logErrors: (Throwable, RetryDetails) => F[Unit] =
    (err, details) => F.pure(log.warn(s"Retrying on query details '$details'", err))

  private[client] val defaultWorthRetry: Throwable => Boolean = {
    case ElasticServerError(code, _) if code != GatewayTimeout     => true
    case ElasticUnexpectedError(code, _) if code != GatewayTimeout => true
    case _                                                         => false
  }

  private[client] def execute(
      req: HttpRequest,
      expectedCodes: Set[StatusCode],
      intent: String,
      isWorthRetry: (Throwable => Boolean)
  ): F[Unit] =
    execute(req, expectedCodes, Set.empty, intent, isWorthRetry).map(_ => ())

  private[client] def handleError[A](req: HttpRequest, intent: String): Throwable => F[A] = {
    case NonFatal(th) =>
      log.error(s"Unexpected response for ElasticSearch '$intent' call. Request: '${req.method} ${req.uri}'", th)
      F.raiseError(ElasticUnexpectedError(StatusCodes.InternalServerError, th.getMessage))
  }

  private[client] def execute(
      req: HttpRequest,
      expectedCodes: Set[StatusCode],
      ignoredCodes: Set[StatusCode],
      intent: String,
      isWorthRetry: (Throwable => Boolean)
  ): F[Boolean] = {
    val response: F[Boolean] = cl(req).handleErrorWith(handleError(req, intent)).flatMap { resp =>
      if (expectedCodes.contains(resp.status)) cl.discardBytes(resp.entity).map(_ => true)
      else if (ignoredCodes.contains(resp.status)) cl.discardBytes(resp.entity).map(_ => false)
      else
        ElasticSearchFailure.fromResponse(resp).flatMap { f =>
          log.error(
            s"Unexpected ElasticSearch response for intent '$intent':\nRequest: '${req.method} ${req.uri}' \nBody: '${f.body}'\nStatus: '${resp.status}'\nResponse: '${f.body}'"
          )
          F.raiseError(f)
        }
    }
    response.retryingOnSomeErrors(isWorthRetry)
  }

  private[client] def indexPath(indices: Set[String]): String =
    if (indices.isEmpty) anyIndexPath
    else indices.map(sanitize(_, allowWildCard = true)).mkString(",")

  /**
    * Replaces the characters ' "\<>|,/?' in the provided index with '_' and drops all '_' prefixes.
    * The wildcard (*) character will be only dropped when ''allowWildCard'' is set to false.
    *
    * @param index the index name to sanitize
    * @param allowWildCard flag to allow wildcard (*) or not.
    */
  private[client] def sanitize(index: String, allowWildCard: Boolean): String = {
    val regex = if (allowWildCard) """[\s|"|\\|<|>|\||,|/|?]""" else """[\s|"|*|\\|<|>|\||,|/|?]"""
    index.replaceAll(regex, "_").dropWhile(_ == '_')
  }
}

object ElasticSearchBaseClient {
  private[client] val docType           = "_doc"
  private[client] val source            = "_source"
  private[client] val anyIndexPath      = "_all"
  private[client] val ignoreUnavailable = "ignore_unavailable"
  private[client] val allowNoIndices    = "allow_no_indices"
  private[client] val trackTotalHits    = "track_total_hits"
}
