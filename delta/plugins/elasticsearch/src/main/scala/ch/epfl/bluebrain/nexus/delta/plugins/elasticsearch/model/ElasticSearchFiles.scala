package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.ioSyntaxLogErrors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{CatsEffectsClasspathResourceUtils, FilesCache}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.CatsEffectsClasspathResourceUtils.ioJsonObjectContentOf
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import org.typelevel.log4cats

trait ElasticSearchFiles {

  /**
    * Default elasticsearch mapping for a view
    */
  def defaultElasticsearchMapping: JsonObject

  /**
    * Default elasticsearch settings for a view
    */
  def defaultElasticsearchSettings: JsonObject

  def emptyResults: IO[Json]

  /** Mapping for the event metrics index */
  def metricsMapping: JsonObject

  /** Settings for the event metrics index */
  def metricsSettings: JsonObject
}

object ElasticSearchFiles {

  implicit private val cl: ClassLoader = getClass.getClassLoader
  implicit private val log: log4cats.Logger[IO] = Logger.cats[ElasticSearchFiles]

  def mk(): IO[ElasticSearchFiles] =
    for {

    } yield new ElasticSearchFiles {
      override def defaultElasticsearchMapping: JsonObject = ???

      /**
       * Default elasticsearch settings for a view
       */
      override def defaultElasticsearchSettings: JsonObject = ???

      override def emptyResults: IO[Json] = ???

      /** Mapping for the event metrics index */
      override def metricsMapping: JsonObject = ???

      /** Settings for the event metrics index */
      override def metricsSettings: JsonObject = ???
    }

//  def mk(): ElasticSearchFiles = new ElasticSearchFiles {
//    override def defaultElasticsearchMapping: IO[JsonObject] =
//      ioJsonObjectContentOf("defaults/default-mapping.json").logErrors("loading default elasticsearch mapping")
//
//    override def defaultElasticsearchSettings: IO[JsonObject] =
//      ioJsonObjectContentOf("defaults/default-settings.json").logErrors("loading default elasticsearch settings")
//
//    override def emptyResults: IO[Json] =
//      ioJsonObjectContentOf("defaults/empty-results.json").logErrors("loading empty elasticsearch results").map(_.asJson)
//
//    override def metricsMapping: IO[JsonObject] =
//      ioJsonObjectContentOf("metrics/metrics-mapping.json").logErrors("loading metrics mapping")
//
//    override def metricsSettings: IO[JsonObject] =
//      ioJsonObjectContentOf("metrics/metrics-settings.json").logErrors("loading metrics settings")
//  }
}
