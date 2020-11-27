package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

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
  def tags: Map[Label, Long]
}

object Resolver {

  /**
    * A resolver that looks only within its own project.
    */
  final case class InProjectResolver(
      id: Iri,
      project: ProjectRef,
      priority: Priority,
      source: Json,
      tags: Map[Label, Long]
  ) extends Resolver

  /**
    * A resolver that can look across several projects.
    */
  final case class CrossProjectResolver(
      id: Iri,
      project: ProjectRef,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identityResolution: IdentityResolution,
      priority: Priority,
      source: Json,
      tags: Map[Label, Long]
  ) extends Resolver

  val context: ContextValue = ContextValue(contexts.resolvers)

  implicit val resolverEncoder: Encoder.AsObject[Resolver] = {
    Encoder.AsObject.instance {
      case InProjectResolver(_, _, priority, _, _)                                                 =>
        JsonObject(
          "priority" -> priority.asJson
        )
      case CrossProjectResolver(_, _, resourceTypes, projects, identityResolution, priority, _, _) =>
        JsonObject(
          "priority"      -> priority.asJson,
          "resourceTypes" -> resourceTypes.asJson,
          "projects"      -> projects.asJson
        ).deepMerge(identityResolution.asJsonObject)
    }
  }

  implicit val resolverJsonLdEncoder: JsonLdEncoder[Resolver] =
    JsonLdEncoder.fromCirce(context)

}
