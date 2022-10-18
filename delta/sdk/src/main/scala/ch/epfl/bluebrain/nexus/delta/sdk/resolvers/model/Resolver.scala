package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.instances.IdentityInstances
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{GraphResourceEncoder, OrderingFields}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import io.circe.syntax._
import io.circe.{Encoder, Json}

/**
  * Enumeration of Resolver types.
  */
sealed trait Resolver extends Product with Serializable {

  /**
    * @return
    *   the resolver id
    */
  def id: Iri

  /**
    * @return
    *   a reference to the project that the resolver belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the resolver priority
    */
  def priority: Priority

  /**
    * @return
    *   the representation of the resolver as posted by the subject
    */
  def source: Json

  /**
    * @return
    *   the collection of tag aliases
    */
  def tags: Tags

  /**
    * @return
    *   The underlying resolver value
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
      tags: Tags
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
      tags: Tags
  ) extends Resolver {
    override def priority: Priority = value.priority
  }

  val context: ContextValue = ContextValue(contexts.resolvers)

  implicit def resolverEncoder(implicit baseUri: BaseUri): Encoder.AsObject[Resolver] = {
    implicit val identityEncoder: Encoder[Identity] = IdentityInstances.identityEncoder
    Encoder.AsObject.instance { r =>
      r.value.asJsonObject.addContext(r.source.topContextValueOrEmpty.excludeRemoteContexts.contextObj)
    }
  }

  implicit def resolverJsonLdEncoder(implicit baseUri: BaseUri): JsonLdEncoder[Resolver] =
    JsonLdEncoder.computeFromCirce(_.id, context)

  implicit val resolverOrderingFields: OrderingFields[Resolver] = OrderingFields.empty

  def graphResourceEncoder(implicit baseUri: BaseUri): GraphResourceEncoder[ResolverState, Resolver, Nothing] =
    GraphResourceEncoder.apply[ResolverState, Resolver](
      Resolvers.entityType,
      (context, state) => state.toResource(context.apiMappings, context.base),
      value => JsonLdContent(value, value.value.source, None)
    )

}
