package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.syntax._
import io.circe.{Encoder, Json}

/**
  * Enumeration of Resolver types.
  */
sealed trait Resolver extends Product with Serializable {

  /**
    * @return the resolver id
    */
  def id: Iri

  /**
    * @return a reference to the project that the resolver belongs to
    */
  def project: ProjectRef

  /**
    * @return the resolver priority
    */
  def priority: Priority

  /**
    * @return the representation of the resolver as posted by the subject
    */
  def source: Json

  /**
    * @return the collection of tag aliases
    */
  def tags: Map[TagLabel, Long]

  /**
    * @return The underlying resolver value
    */
  def value: ResolverValue
}

object Resolver {

  /**
    * A resolver that looks only within its own project.
    */
  final case class InProjectResolver(
      id: Iri,
      project: ProjectRef,
      value: InProjectValue,
      source: Json,
      tags: Map[TagLabel, Long]
  ) extends Resolver {
    override def priority: Priority = value.priority
  }

  /**
    * A resolver that can look across several projects.
    */
  final case class CrossProjectResolver(
      id: Iri,
      project: ProjectRef,
      value: CrossProjectValue,
      source: Json,
      tags: Map[TagLabel, Long]
  ) extends Resolver {
    override def priority: Priority = value.priority
  }

  val context: ContextValue = ContextValue(contexts.resolvers)

  implicit def resolverEncoder(implicit baseUri: BaseUri): Encoder.AsObject[Resolver] = {
    implicit val identityEncoder: Encoder[Identity] = Identity.identityEncoder
    Encoder.AsObject.instance { r =>
      r.value.asJsonObject.addContext(r.source.topContextValueOrEmpty.contextObj)
    }
  }

  implicit def resolverJsonLdEncoder(implicit baseUri: BaseUri): JsonLdEncoder[Resolver] =
    JsonLdEncoder.computeFromCirce(_.id, context)

}
