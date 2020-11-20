package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import io.circe.{Decoder, Encoder, Json}

/**
  * Enumeration of resolver types
  */
sealed trait ResolverType extends Product with Serializable

object ResolverType {

  /**
    * Resolver within a same project
    */
  case object InProject extends ResolverType

  /**
    * Resolver across multiple projects
    */
  case object CrossProject extends ResolverType

  implicit final val resolverTypeEncoder: Encoder[ResolverType] = Encoder.instance {
    case InProject    => Json.fromString("InProject")
    case CrossProject => Json.fromString("CrossProject")
  }

  implicit final val resolverTypeDeccoder: Decoder[ResolverType] = Decoder.decodeString.emap {
    case "InProject"    => Right(InProject)
    case "CrossProject" => Right(CrossProject)
  }

}
