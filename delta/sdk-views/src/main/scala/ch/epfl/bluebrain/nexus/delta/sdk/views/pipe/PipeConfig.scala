package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

/**
  * Pipe configuration
  * @param availablePipes
  *   available pipes in the system
  */
final case class PipeConfig(availablePipes: Map[String, Pipe]) extends AnyVal

object PipeConfig {

  /**
    * Constructs a pipe configuration and validate that pipe name is a unique identifier
    */
  def apply(pipes: Set[Pipe]): Either[String, PipeConfig] = {
    val init: Either[String, Map[String, Pipe]] = Right(Map.empty[String, Pipe])
    pipes
      .foldLeft(init) {
        case (l @ Left(_), _)                              => l
        case (Right(map), pipe) if map.contains(pipe.name) => Left(s"'${pipe.name}' is defined multiple times.")
        case (Right(map), pipe)                            => Right(map + (pipe.name -> pipe))
      }
      .map(PipeConfig(_))
  }

  def apply(pipes: Pipe*): Either[String, PipeConfig] = apply(pipes.toSet)

  val corePipes =
    Set(
      DataConstructQuery.pipe,
      DiscardMetadata.pipe,
      FilterBySchema.pipe,
      FilterByType.pipe,
      FilterDeprecated.pipe,
      SelectPredicates.pipe,
      SourceAsText.pipe
    )

  val coreConfig: Either[String, PipeConfig] = PipeConfig(corePipes)

}
