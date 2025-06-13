package ai.senscience.nexus.delta.plugins.search.model

import ai.senscience.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ai.senscience.nexus.delta.plugins.search.model.SearchConfigError.{InvalidRebuildStrategy, InvalidSparqlConstructQuery, InvalidSuites}
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils.loadJsonAs
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.{Interval, RebuildStrategy}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.TemplateSparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Label, ProjectRef}
import com.typesafe.config.Config
import io.circe.syntax.KeyOps
import io.circe.{Encoder, JsonObject}
import pureconfig.{ConfigReader, ConfigSource}

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

final case class SearchConfig(
    indexing: IndexingConfig,
    fields: Option[JsonObject],
    defaults: Defaults,
    suites: SearchConfig.Suites
)

object SearchConfig {

  type Suite  = Set[ProjectRef]
  type Suites = Map[Label, Suite]

  case class NamedSuite(name: Label, suite: Suite)
  implicit private val suitesMapReader: ConfigReader[Suites] = Label.labelMapReader[Suite]

  implicit val suiteEncoder: Encoder[NamedSuite]         =
    Encoder[JsonObject].contramap(s => JsonObject("projects" := s.suite, "name" := s.name))
  implicit val suiteLdEncoder: JsonLdEncoder[NamedSuite] = JsonLdEncoder.computeFromCirce(ContextValue(contexts.suites))

  implicit val namedSuiteHttpResponseFields: HttpResponseFields[NamedSuite] = HttpResponseFields.defaultOk

  /**
    * Converts a [[Config]] into an [[SearchConfig]]
    */
  def load(config: Config): IO[SearchConfig] = {
    val pluginConfig                    = config.getConfig("plugins.search")
    def getFilePath(configPath: String) = Path.of(pluginConfig.getString(configPath))
    def loadSuites                      = {
      val suiteSource = ConfigSource.fromConfig(pluginConfig).at("suites")
      IO.fromEither(suiteSource.load[Suites].leftMap(InvalidSuites))
    }
    for {
      fields        <- loadOption(pluginConfig, "fields", loadJsonAs[JsonObject])
      resourceTypes <- loadJsonAs[IriFilter](getFilePath("indexing.resource-types"))
      mapping       <- loadJsonAs[JsonObject](getFilePath("indexing.mapping"))
      settings      <- loadOption(pluginConfig, "indexing.settings", loadJsonAs[JsonObject])
      query         <- loadSparqlQuery(getFilePath("indexing.query"))
      context       <- loadOption(pluginConfig, "indexing.context", loadJsonAs[JsonObject])
      rebuild       <- loadRebuildStrategy(pluginConfig)
      defaults      <- loadDefaults(pluginConfig)
      suites        <- loadSuites
    } yield SearchConfig(
      IndexingConfig(
        resourceTypes,
        mapping,
        settings = settings,
        query = query,
        context = ContextObject(context.getOrElse(JsonObject.empty)),
        rebuildStrategy = rebuild
      ),
      fields,
      defaults,
      suites
    )
  }

  private def loadOption[A](config: Config, path: String, io: Path => IO[A]) =
    if (config.hasPath(path))
      io(Path.of(config.getString(path))).map(Some(_))
    else IO.none

  private def loadSparqlQuery(filePath: Path): IO[SparqlConstructQuery] =
    for {
      content <- FileUtils.loadAsString(filePath)
      json    <- IO.fromEither(TemplateSparqlConstructQuery(content).leftMap { e =>
                   InvalidSparqlConstructQuery(filePath, e)
                 })
    } yield json

  private def loadDefaults(config: Config): IO[Defaults] =
    IO.pure(ConfigSource.fromConfig(config).at("defaults").loadOrThrow[Defaults])

  /**
    * Load the rebuild strategy from the search config. If either of the required fields is null, missing, or not a
    * correct finite duration, there will be no rebuild strategy. If both finite durations are present, then the
    * specified rebuild strategy must be greater or equal to the min rebuild interval.
    */
  private def loadRebuildStrategy(config: Config): IO[Option[RebuildStrategy]] =
    (
      readFiniteDuration(config, "indexing.rebuild-strategy"),
      readFiniteDuration(config, "indexing.min-interval-rebuild")
    ).traverseN { case (rebuild, minIntervalRebuild) =>
      IO.raiseWhen(rebuild lt minIntervalRebuild)(InvalidRebuildStrategy(rebuild, minIntervalRebuild)) >>
        IO.pure(Interval(rebuild))
    }

  private def readFiniteDuration(config: Config, path: String): Option[FiniteDuration] =
    Try(
      ConfigSource.fromConfig(config).at(path).loadOrThrow[FiniteDuration]
    ).toOption

  final case class IndexingConfig(
      resourceTypes: IriFilter,
      mapping: JsonObject,
      settings: Option[JsonObject],
      query: SparqlConstructQuery,
      context: ContextObject,
      rebuildStrategy: Option[RebuildStrategy]
  )

}
