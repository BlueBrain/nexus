package ch.epfl.bluebrain.nexus.ship.search

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.TemplateSparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchScopeInitialization
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfigError.{InvalidJsonError, InvalidSparqlConstructQuery, LoadingFileError}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.IriFilter
import ch.epfl.bluebrain.nexus.ship.config.SearchConfig
import io.circe.parser.decode
import io.circe.{Decoder, JsonObject}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration._
import scala.util.Try

object SearchWiring {

  private val client = HttpClient.newHttpClient()

  private def githubPrefix(commit: String) =
    s"https://raw.githubusercontent.com/BlueBrain/nexus/$commit/tests/docker/config/search"

  private def getAsString(url: String) = {
    val request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
    IO.fromEither(
      Try(client.send(request, HttpResponse.BodyHandlers.ofString())).toEither.leftMap(LoadingFileError(url, _))
    )
  }

  private def loadExternalConfig[A: Decoder](url: String): IO[A] =
    for {
      content <- getAsString(url)
      value   <- IO.fromEither(decode[A](content.body()).leftMap { e => InvalidJsonError(url, e.getMessage) })
    } yield value

  private def loadSparqlQuery(url: String): IO[SparqlConstructQuery] =
    for {
      content <- getAsString(url)
      value   <- IO.fromEither(TemplateSparqlConstructQuery(content.body()).leftMap { e =>
                   InvalidSparqlConstructQuery(url, e)
                 })
    } yield value

  private def indexingConfig(commit: String, rebuildInterval: FiniteDuration) = {
    val prefix = githubPrefix(commit)
    for {
      resourceTypes <- loadExternalConfig[IriFilter](s"$prefix/resource-types.json")
      mapping       <- loadExternalConfig[JsonObject](s"$prefix/mapping.json")
      settings      <- loadExternalConfig[JsonObject](s"$prefix/settings.json")
      query         <- loadSparqlQuery(s"$prefix/construct-query.sparql")
      context       <- loadExternalConfig[JsonObject](s"$prefix/search-context.json")
    } yield IndexingConfig(
      resourceTypes,
      mapping,
      settings = Some(settings),
      query = query,
      context = ContextObject(context),
      rebuildStrategy = Some(Interval(rebuildInterval))
    )
  }

  def searchInitializer(
      compositeViews: CompositeViews,
      serviceAccount: ServiceAccount,
      config: SearchConfig,
      defaults: Defaults
  )(implicit baseUri: BaseUri): IO[SearchScopeInitialization] =
    indexingConfig(config.commit, config.rebuildInterval).map { config =>
      new SearchScopeInitialization(compositeViews, config, serviceAccount, defaults)
    }

}
