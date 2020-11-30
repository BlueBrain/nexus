package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import io.circe.{Decoder, Encoder, Json}

/**
  * Enumeration of resolver types
  */
sealed trait ResolverType extends Product with Serializable {

  /**
    * The JSON-LD types for the given resolver type
    * @return
    */
  def types: Set[Iri]
}

object ResolverType {

  /**
    * Resolver within a same project
    */
  case object InProject extends ResolverType {
    override def types: Set[Iri] = Set(nxv.Resolver, nxv.InProject)
  }

  /**
    * Resolver across multiple projects
    */
  case object CrossProject extends ResolverType {
    override def types: Set[Iri] = Set(nxv.Resolver, nxv.CrossProject)
  }

  implicit final val resolverTypeEncoder: Encoder[ResolverType] = Encoder.instance {
    case InProject    => Json.fromString("InProject")
    case CrossProject => Json.fromString("CrossProject")
  }

  implicit final val resolverTypeDeccoder: Decoder[ResolverType] = Decoder.decodeString.emap {
    case "InProject"    => Right(InProject)
    case "CrossProject" => Right(CrossProject)
  }

}
