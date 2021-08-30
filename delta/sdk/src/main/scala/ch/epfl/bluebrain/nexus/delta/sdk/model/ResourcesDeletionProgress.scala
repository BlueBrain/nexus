package ch.epfl.bluebrain.nexus.delta.sdk.model

import io.circe.{Decoder, Encoder}

/**
  * Enumeration of possible deletion progress
  */
sealed trait ResourcesDeletionProgress extends Product with Serializable {

  /**
    * @return true if the deletion has been completed, false otherwise
    */
  def completed: Boolean =
    this match {
      case ResourcesDeletionProgress.ResourcesDeleted => true
      case _                                          => false
    }
}

object ResourcesDeletionProgress {

  /**
    * The deletion process just started
    */
  final case object Deleting extends ResourcesDeletionProgress

  /**
    * Resources content was deleted (e.g.: files content, views indices)
    */
  final case object ResourcesDataDeleted extends ResourcesDeletionProgress

  /**
    * Resources caches were deleted
    */
  final case object CachesDeleted extends ResourcesDeletionProgress

  /**
    * The deletion process was completed
    */
  final case object ResourcesDeleted extends ResourcesDeletionProgress

  type Deleting             = Deleting.type
  type ResourcesDataDeleted = ResourcesDataDeleted.type
  type CachesDeleted        = CachesDeleted.type
  type ResourcesDeleted     = ResourcesDeleted.type

  implicit val resourcesDeletionProgressEncoder: Encoder[ResourcesDeletionProgress] =
    Encoder.encodeString.contramap(_.toString)

  implicit val resourcesDeletionProgressDecoder: Decoder[ResourcesDeletionProgress] =
    Decoder.decodeString.emap {
      case "Deleting"             => Right(Deleting)
      case "ResourcesDataDeleted" => Right(ResourcesDataDeleted)
      case "CachesDeleted"        => Right(CachesDeleted)
      case "ResourcesDeleted"     => Right(ResourcesDeleted)
      case other                  => Left(s"'$other' is not a ResourcesDeletionProgress")
    }
}
