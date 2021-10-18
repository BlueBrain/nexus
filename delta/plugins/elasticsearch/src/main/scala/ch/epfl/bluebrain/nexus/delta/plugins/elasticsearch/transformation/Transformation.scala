package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.transformation

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import com.typesafe.config.Config
import monix.bio.Task
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.error.ConfigReaderFailures

import scala.annotation.nowarn

trait Transformation {

  type Context

  /**
    * Identifier of the transformation
    */
  def name: String

  /**
    * Parse and validate the provided config
    */
  def parse(config: Config): Either[ConfigReaderFailures, Context]

  /**
    * Apply the provided context and create the transformation task
    */
  def run(context: Context, data: IndexingData): Task[Option[IndexingData]]

}

object Transformation {

  type TransformationResult = Task[Option[IndexingData]]

  // To validate
  def parse(definitions: List[TransformationDefinition], availableTransformations: Map[String, Transformation]) = ???

  private val notFound: IndexingData => TransformationResult = (_: IndexingData) => Task.none

  def run(
      definitions: List[TransformationDefinition],
      availableTransformations: Map[String, Transformation]
  ): IndexingData => TransformationResult = {
    val transformations: List[IndexingData => TransformationResult] = definitions.map { d =>
      availableTransformations.get(d.name).fold(notFound) { t =>
        //TODO Handle error on getting config
        val config = t.parse(d.config).toOption.get
        t.run(config, _)
      }
    }
    data: IndexingData =>
      transformations.foldLeftM(Option(data)) {
        case (Some(d), t) => t(d)
        case (None, _)    => Task.none
      }
  }

  def withoutContext(transformationName: String, f: IndexingData => TransformationResult): Transformation =
    new Transformation {

      override type Context = Unit

      override def name: String = transformationName

      override def parse(config: Config): Either[ConfigReaderFailures, Context] = Right(())

      override def run(context: Context, data: IndexingData): TransformationResult = f(data)
    }

  @nowarn("cat=unused")
  def withContext[C0: ConfigReader](
      transformationName: String,
      f: (C0, IndexingData) => TransformationResult
  ): Transformation = new Transformation {

    override type Context = C0

    override def name: String = transformationName

    override def parse(config: Config): Either[ConfigReaderFailures, Context] =
      ConfigSource.fromConfig(config).load[Context]

    override def run(context: Context, data: IndexingData): TransformationResult = f(context, data)
  }

  val excludeMetadata: Transformation =
    withoutContext(
      "excludeMetadata",
      (data: IndexingData) => Task.some(data.copy(metadataGraph = Graph.empty))
    )

  val excludeDeprecated: Transformation =
    withoutContext(
      "excludeDeprecated",
      (data: IndexingData) => Task.pure(Option.when(!data.deprecated)(data))
    )

}
