package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.pipe

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import io.circe.syntax._
import io.circe.{Decoder, JsonObject}
import monix.bio.Task

trait Pipe {

  type Context

  /**
    * Identifier of the pipe
    */
  def name: String

  /**
    * Parse and validate the provided config
    */
  def parse(config: JsonObject): Either[io.circe.Error, Context]

  /**
    * Apply the provided context and create the pipe task
    */
  def run(context: Context, data: IndexingData): Task[Option[IndexingData]]

}

object Pipe {

  type PipeResult = Task[Option[IndexingData]]

  // To validate
  def parse(definitions: List[PipeDef], availablePipes: Map[String, Pipe]) = ???

  private val notFound: IndexingData => PipeResult = (_: IndexingData) => Task.none

  def run(
      definitions: List[PipeDef],
      availablePipe: Map[String, Pipe]
  ): IndexingData => PipeResult = {
    val pipes: List[IndexingData => PipeResult] = definitions.map { d =>
      availablePipe.get(d.name).fold(notFound) { t =>
        //TODO Handle error on getting config
        val config = t.parse(d.config).toOption.get
        t.run(config, _)
      }
    }
    data: IndexingData =>
      pipes.foldLeftM(Option(data)) {
        case (Some(d), t) => t(d)
        case (None, _)    => Task.none
      }
  }

  def withoutContext(pipeName: String, f: IndexingData => PipeResult): Pipe =
    new Pipe {

      override type Context = Unit

      override def name: String = pipeName

      override def parse(config: JsonObject): Either[io.circe.Error, Context] = Right(())

      override def run(context: Context, data: IndexingData): PipeResult = f(data)
    }

  def withContext[C0](
      pipeName: String,
      f: (C0, IndexingData) => PipeResult
  )(implicit decoder: Decoder[C0]): Pipe = new Pipe {

    override type Context = C0

    override def name: String = pipeName

    override def parse(config: JsonObject): Either[io.circe.Error, Context] =
      decoder.decodeJson(config.asJson)

    override def run(context: Context, data: IndexingData): PipeResult = f(context, data)
  }

  val excludeMetadata: Pipe =
    withoutContext(
      "excludeMetadata",
      (data: IndexingData) => Task.some(data.copy(metadataGraph = Graph.empty))
    )

  val excludeDeprecated: Pipe =
    withoutContext(
      "excludeDeprecated",
      (data: IndexingData) => Task.pure(Option.when(!data.deprecated)(data))
    )

}
