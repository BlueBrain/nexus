package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.{Encoder, Json, JsonObject}

import java.util.UUID
import scala.annotation.nowarn

/**
  * A target projection for [[CompositeView]].
  */
sealed trait CompositeViewProjection extends Product with Serializable {

  /**
    * @return the id of the projection
    */
  def id: Iri

  /**
    * @return the uuid of the projection
    */
  def uuid: UUID

  /**
    * SPARQL query used to create values indexed into the projection.
    */
  def query: String

  /**
    * @return the schemas to filter by, empty means all
    */
  def resourceSchemas: Set[Iri]

  /**
    * @return the resource types to filter by, empty means all
    */
  def resourceTypes: Set[Iri]

  /**
    * @return the optional tag to filter by
    */
  def resourceTag: Option[TagLabel]

  /**
    * @return whether to include deprecated resources
    */
  def includeMetadata: Boolean

  /**
    * @return whether to include resource metadata
    */
  def includeDeprecated: Boolean

  /**
    * @return permission required to query the projection
    */
  def permission: Permission

  /**
    * @return the type of the projection
    */
  def tpe: ProjectionType
}

object CompositeViewProjection {

  /**
    * An ElasticSearch projection for [[CompositeView]].
    */
  final case class ElasticSearchProjection(
      id: Iri,
      uuid: UUID,
      query: String,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission,
      sourceAsText: Boolean = false,
      mapping: JsonObject,
      settings: Option[JsonObject] = None,
      context: Json
  ) extends CompositeViewProjection {

    override def tpe: ProjectionType = ElasticSearchProjectionType
  }

  /**
    * A Sparql projection for [[CompositeView]].
    */
  final case class SparqlProjection(
      id: Iri,
      uuid: UUID,
      query: String,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[TagLabel],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission
  ) extends CompositeViewProjection {

    override def tpe: ProjectionType = SparqlProjectionType
  }

  @nowarn("cat=unused")
  implicit final val projectionEncoder: Encoder.AsObject[CompositeViewProjection] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val config: Configuration = Configuration(
      transformMemberNames = {
        case "id" => keywords.id
        case other => other
      },
      transformConstructorNames = identity,
      useDefaults = false,
      discriminator = Some(keywords.tpe),
      strictDecoding = false
    )
    deriveConfiguredEncoder[CompositeViewProjection]
  }

}
