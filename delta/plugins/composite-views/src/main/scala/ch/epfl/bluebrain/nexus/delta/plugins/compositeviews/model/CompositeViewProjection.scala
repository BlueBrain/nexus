package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel.IndexGroup
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.{Encoder, JsonObject}

import java.util.UUID
import scala.annotation.nowarn

/**
  * A target projection for [[CompositeView]].
  */
sealed trait CompositeViewProjection extends Product with Serializable {

  /**
    * @return
    *   the id of the projection
    */
  def id: Iri

  /**
    * @return
    *   the uuid of the projection
    */
  def uuid: UUID

  /**
    * SPARQL query used to create values indexed into the projection.
    */
  def query: SparqlConstructQuery

  /**
    * @return
    *   the schemas to filter by, empty means all
    */
  def resourceSchemas: Set[Iri]

  /**
    * @return
    *   the resource types to filter by, empty means all
    */
  def resourceTypes: Set[Iri]

  /**
    * @return
    *   the optional tag to filter by
    */
  def resourceTag: Option[UserTag]

  /**
    * @return
    *   whether to include deprecated resources
    */
  def includeMetadata: Boolean

  /**
    * @return
    *   whether to include resource metadata
    */
  def includeDeprecated: Boolean

  /**
    * @return
    *   permission required to query the projection
    */
  def permission: Permission

  /**
    * @return
    *   the type of the projection
    */
  def tpe: ProjectionType

  /**
    * @return
    *   Some(projection) if the current projection is an [[SparqlProjection]], None otherwise
    */
  def asSparql: Option[SparqlProjection]

  /**
    * @return
    *   Some(projection) if the current projection is an [[ElasticSearchProjection]], None otherwise
    */
  def asElasticSearch: Option[ElasticSearchProjection]
}

object CompositeViewProjection {

  /**
    * The templating id for the projection query
    */
  val idTemplating = "{resource_id}"

  /**
    * An ElasticSearch projection for [[CompositeView]].
    */
  final case class ElasticSearchProjection(
      id: Iri,
      uuid: UUID,
      query: SparqlConstructQuery,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[UserTag],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission,
      indexGroup: Option[IndexGroup],
      mapping: JsonObject,
      settings: Option[JsonObject] = None,
      context: ContextObject
  ) extends CompositeViewProjection {

    override def tpe: ProjectionType                              = ElasticSearchProjectionType
    override def asSparql: Option[SparqlProjection]               = None
    override def asElasticSearch: Option[ElasticSearchProjection] = Some(this)
  }

  /**
    * A Sparql projection for [[CompositeView]].
    */
  final case class SparqlProjection(
      id: Iri,
      uuid: UUID,
      query: SparqlConstructQuery,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[UserTag],
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission
  ) extends CompositeViewProjection {

    override def tpe: ProjectionType                              = SparqlProjectionType
    override def asSparql: Option[SparqlProjection]               = Some(this)
    override def asElasticSearch: Option[ElasticSearchProjection] = None
  }

  @nowarn("cat=unused")
  implicit final val projectionEncoder: Encoder.AsObject[CompositeViewProjection] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val config: Configuration = Configuration(
      transformMemberNames = {
        case "id"  => keywords.id
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
