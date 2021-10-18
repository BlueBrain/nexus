package ch.epfl.bluebrain.nexus.delta.plugins.search.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.TemplateSparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfigError.{InvalidJsonError, InvalidSparqlConstructQuery, LoadingFileError}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import com.typesafe.config.Config
import io.circe.parser._
import io.circe.{Decoder, JsonObject}
import monix.bio.IO

import java.nio.file.{Files, Path}
import scala.util.Try

final case class SearchConfig(indexing: IndexingConfig, fields: Option[JsonObject])

object SearchConfig {

  /**
    * Converts a [[Config]] into an [[SearchConfig]]
    */
  def load(config: Config): IO[SearchConfigError, SearchConfig] = {
    val pluginConfig = config.getConfig("plugins.search")
    for {
      fields        <- loadOption(pluginConfig, "fields", loadExternalConfig[JsonObject])
      resourceTypes <- loadExternalConfig[Set[Iri]](pluginConfig.getString("indexing.resource-types"))
      mapping       <- loadExternalConfig[JsonObject](pluginConfig.getString("indexing.mapping"))
      settings      <- loadOption(pluginConfig, "indexing.settings", loadExternalConfig[JsonObject])
      query         <- loadSparqlQuery(pluginConfig.getString("indexing.query"))
      context       <- loadOption(pluginConfig, "indexing.context", loadExternalConfig[JsonObject])
    } yield SearchConfig(
      IndexingConfig(
        resourceTypes,
        mapping,
        settings = settings,
        query = query,
        context = ContextObject(context.getOrElse(JsonObject.empty))
      ),
      fields
    )
  }

  private def loadOption[A](config: Config, path: String, io: String => IO[SearchConfigError, A]) =
    if (config.hasPath(path))
      io(config.getString(path)).map(Some(_))
    else IO.none

  private def loadExternalConfig[A: Decoder](filePath: String): IO[SearchConfigError, A] =
    for {
      content <- IO.fromEither(
                   Try(Files.readString(Path.of(filePath))).toEither.leftMap(LoadingFileError(filePath, _))
                 )
      json    <- IO.fromEither(decode[A](content).leftMap { e => InvalidJsonError(filePath, e.getMessage) })
    } yield json

  private def loadSparqlQuery(filePath: String): IO[SearchConfigError, SparqlConstructQuery] =
    for {
      content <- IO.fromEither(
                   Try(Files.readString(Path.of(filePath))).toEither.leftMap(LoadingFileError(filePath, _))
                 )
      json    <- IO.fromEither(TemplateSparqlConstructQuery(content).leftMap { e =>
                   InvalidSparqlConstructQuery(filePath, e)
                 })
    } yield json

  final case class IndexingConfig(
      resourceTypes: Set[Iri],
      mapping: JsonObject,
      settings: Option[JsonObject],
      query: SparqlConstructQuery,
      context: ContextObject
  )

}
