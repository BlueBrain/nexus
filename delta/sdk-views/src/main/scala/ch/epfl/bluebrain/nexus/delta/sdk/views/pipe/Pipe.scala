package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.PipeError.{InvalidContext, PipeNotFound}
import monix.bio.{IO, Task}

/**
  * Transformation unit of a pipeline within a view
  */
trait Pipe {

  /**
    * The context to apply for the pipeline
    */
  type Config

  /**
    * Identifier of the pipe
    */
  def name: String

  /**
    * Parse and validate the provided config
    */
  def parse(config: Option[ExpandedJsonLd]): Either[InvalidContext, Config]

  /**
    * Apply the provided config and create the pipe task
    */
  def run(config: Config, data: IndexingData): Task[Option[IndexingData]]

  /**
    * Parse the config and run the resulting context with the provided data
    * @param config
    *   the config to parse
    * @param data
    *   the data to apply the pipe on
    */
  def parseAndRun(config: Option[ExpandedJsonLd], data: IndexingData): Task[Option[IndexingData]] =
    Task.fromEither(parse(config)).flatMap(run(_, data))

}

object Pipe {

  type PipeResult = Task[Option[IndexingData]]

  /**
    * Validate the definitions against the available pipes
    * @param definitions
    *   the definitions to validate
    * @param availablePipes
    *   the available pipes
    */
  def validate(definitions: List[PipeDef], availablePipes: Map[String, Pipe]): Either[PipeError, Unit] =
    definitions.traverse { d =>
      availablePipes.get(d.name) match {
        case None    => Left(PipeNotFound(d.name))
        case Some(t) => t.parse(d.context)
      }
    }.void

  /**
    * Parse and fold the provided definitions to create the pipeline function
    * @param definitions
    *   the definitions to run
    * @param availablePipes
    *   the available pipes
    * @return
    */
  def run(
      definitions: List[PipeDef],
      availablePipes: Map[String, Pipe]
  ): IO[PipeError, IndexingData => PipeResult] = {
    definitions
      .traverse { d =>
        availablePipes.get(d.name) match {
          case None    => IO.raiseError(PipeNotFound(d.name))
          case Some(t) =>
            IO.fromEither(t.parse(d.context))
              .map { c => t.run(c, _) }
        }
      }
      .map { pipes => data: IndexingData =>
        pipes.foldLeftM(Option(data)) {
          case (Some(d), t) => t(d)
          case (None, _)    => Task.none
        }
      }
  }

  /**
    * Create a pipe which does not need a context
    * @param pipeName
    *   the pipe name
    * @param f
    *   the pipe function
    */
  def withoutContext(pipeName: String, f: IndexingData => PipeResult): Pipe =
    new Pipe {

      override type Context = Unit

      override def name: String = pipeName

      override def parse(config: Option[ExpandedJsonLd]): Either[InvalidContext, Context] =
        config match {
          case Some(_) => Left(InvalidContext(pipeName, "No config is needed."))
          case None    => Right(())
        }

      override def run(context: Context, data: IndexingData): PipeResult = f(data)
    }

  /**
    * Creates a pipe relying on a context to operate
    * @param pipeName
    *   the pipe name
    * @param f
    *   the pipe function
    */
  def withContext[C0](
      pipeName: String,
      f: (C0, IndexingData) => PipeResult
  )(implicit decoder: JsonLdDecoder[C0]): Pipe = new Pipe {

    override type Context = C0

    override def name: String = pipeName

    override def parse(config: Option[ExpandedJsonLd]): Either[InvalidContext, Context] =
      config match {
        case Some(c) =>
          c.to[C0].leftMap { e =>
            InvalidContext(pipeName, e.getMessage())
          }
        case None    => Left(InvalidContext(pipeName, "A context is required."))
      }

    override def run(context: Context, data: IndexingData): PipeResult = f(context, data)
  }

  /**
    * Excludes metadata from being indexed
    */
  val excludeMetadata: Pipe =
    withoutContext(
      "excludeMetadata",
      (data: IndexingData) => Task.some(data.copy(metadataGraph = Graph.empty))
    )

  /**
    * Filters out deprecated resources
    */
  val excludeDeprecated: Pipe =
    withoutContext(
      "excludeDeprecated",
      (data: IndexingData) => Task.pure(Option.when(!data.deprecated)(data))
    )
}
