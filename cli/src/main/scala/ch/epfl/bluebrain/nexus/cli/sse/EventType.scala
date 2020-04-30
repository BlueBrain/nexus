package ch.epfl.bluebrain.nexus.cli.sse

import io.circe.Decoder

/**
  * Enumeration of known event types.
  */
sealed trait EventType extends Product with Serializable

object EventType {

  final case object Created               extends EventType
  final case object Updated               extends EventType
  final case object Deprecated            extends EventType
  final case object FileCreated           extends EventType
  final case object FileUpdated           extends EventType
  final case object FileDigestUpdated     extends EventType
  final case object FileAttributesUpdated extends EventType
  final case object TagAdded              extends EventType

  /**
    * Attempts to create an EventType from the provided string.
    */
  final def apply(string: String): Either[String, EventType] =
    string match {
      case "Created"               => Right(Created)
      case "Updated"               => Right(Updated)
      case "Deprecated"            => Right(Deprecated)
      case "FileCreated"           => Right(FileCreated)
      case "FileUpdated"           => Right(FileUpdated)
      case "FileDigestUpdated"     => Right(FileDigestUpdated)
      case "FileAttributesUpdated" => Right(FileAttributesUpdated)
      case "TagAdded"              => Right(TagAdded)
      case other                   => Left(s"'$other' does not match a known event type")
    }

  implicit final val eventTypeDecoder: Decoder[EventType] =
    Decoder.decodeString.emap(apply)
}
