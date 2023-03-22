package ch.epfl.bluebrain.nexus.delta.plugins.search.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.TemplateSparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfigError.{InvalidJsonError, InvalidSparqlConstructQuery, InvalidSuites, LoadingFileError}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import com.typesafe.config.Config
import io.circe.parser._
import io.circe.{Decoder, JsonObject}
import monix.bio.IO
import pureconfig.configurable.genericMapReader
import pureconfig.error.CannotConvert
import pureconfig.{ConfigReader, ConfigSource}

import java.nio.file.{Files, Path}
import scala.util.Try

final case class SearchConfig(
    indexing: IndexingConfig,
    fields: Option[JsonObject],
    defaults: Defaults,
    suites: SearchConfig.Suites
)

object SearchConfig {

  implicit val projectRefReader: ConfigReader[ProjectRef] = ConfigReader.fromString { value =>
    value.split("/").toList match {
      case orgStr :: projectStr :: Nil =>
        (Label(orgStr), Label(projectStr))
          .mapN(ProjectRef(_, _))
          .leftMap(err => CannotConvert(value, classOf[ProjectRef].getSimpleName, err.getMessage))
      case _                           =>
        Left(CannotConvert(value, classOf[ProjectRef].getSimpleName, "Wrong format"))
    }
  }

  type Suites = Map[Label, Set[ProjectRef]]
  implicit private val suitesMapReader: ConfigReader[Suites] =
    genericMapReader(str => Label(str).leftMap(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage)))

  /**
    * Converts a [[Config]] into an [[SearchConfig]]
    */
  def load(config: Config): IO[SearchConfigError, SearchConfig] = {
    val pluginConfig = config.getConfig("plugins.search")
    val suiteSource  = ConfigSource.fromConfig(pluginConfig).at("suites")
    for {
      fields        <- loadOption(pluginConfig, "fields", loadExternalConfig[JsonObject])
      resourceTypes <- loadExternalConfig[Set[Iri]](pluginConfig.getString("indexing.resource-types"))
      mapping       <- loadExternalConfig[JsonObject](pluginConfig.getString("indexing.mapping"))
      settings      <- loadOption(pluginConfig, "indexing.settings", loadExternalConfig[JsonObject])
      query         <- loadSparqlQuery(pluginConfig.getString("indexing.query"))
      context       <- loadOption(pluginConfig, "indexing.context", loadExternalConfig[JsonObject])
      defaults      <- loadDefaults(pluginConfig)
      suites        <- IO.fromEither(suiteSource.load[Suites]).mapError(InvalidSuites)
    } yield SearchConfig(
      IndexingConfig(
        resourceTypes,
        mapping,
        settings = settings,
        query = query,
        context = ContextObject(context.getOrElse(JsonObject.empty))
      ),
      fields,
      defaults,
      suites
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

  private def loadDefaults(config: Config): IO[SearchConfigError, Defaults] =
    IO.fromEither(
      Try(
        ConfigSource.fromConfig(config).at("defaults").loadOrThrow[Defaults]
        // TODO: Use a correct error
      ).toEither.leftMap(_ => InvalidJsonError("string", "string"))
    )

  final case class IndexingConfig(
      resourceTypes: Set[Iri],
      mapping: JsonObject,
      settings: Option[JsonObject],
      query: SparqlConstructQuery,
      context: ContextObject
  )

}
