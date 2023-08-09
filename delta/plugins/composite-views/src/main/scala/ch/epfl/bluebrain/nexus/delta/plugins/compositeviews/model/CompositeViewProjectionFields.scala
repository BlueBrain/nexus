package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.{ElasticSearchProjectionType, SparqlProjectionType}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.TemplateSparqlConstructQuery._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel.IndexGroup
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.{Encoder, JsonObject}

import java.util.UUID
import scala.annotation.nowarn

/**
  * Necessary fields needed to create/update a composite view projection.
  */
sealed trait CompositeViewProjectionFields {

  /**
    * @return
    *   the id
    */
  def id: Option[Iri]

  /**
    * @return
    *   projection query
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
  def includeDeprecated: Boolean

  /**
    * @return
    *   whether to include resource metadata
    */
  def includeMetadata: Boolean

  /**
    * @return
    *   permission required to query the projection
    */
  def permission: Permission

  /**
    * @return
    *   the projection type
    */
  def tpe: ProjectionType

  /**
    * @return
    *   transform from [[CompositeViewProjectionFields]] to [[CompositeViewProjection]]
    */
  def toProjection(uuid: UUID, generatedId: Iri, indexingRev: Int): CompositeViewProjection
}

object CompositeViewProjectionFields {

  /**
    * Necessary fields to create/update an ElasticSearch projection.
    */
  final case class ElasticSearchProjectionFields(
      id: Option[Iri] = None,
      query: SparqlConstructQuery,
      indexGroup: Option[IndexGroup],
      mapping: JsonObject,
      context: ContextObject,
      settings: Option[JsonObject] = None,
      resourceSchemas: Set[Iri] = Set.empty,
      resourceTypes: Set[Iri] = Set.empty,
      resourceTag: Option[UserTag] = None,
      includeDeprecated: Boolean = false,
      includeMetadata: Boolean = false,
      includeContext: Boolean = false,
      permission: Permission = permissions.query
  ) extends CompositeViewProjectionFields {
    override def tpe: ProjectionType = ElasticSearchProjectionType

    override def toProjection(uuid: UUID, generatedId: Iri, indexingRev: Int): CompositeViewProjection =
      ElasticSearchProjection(
        id.getOrElse(generatedId),
        uuid,
        indexingRev,
        query,
        resourceSchemas,
        resourceTypes,
        resourceTag,
        includeMetadata,
        includeDeprecated,
        includeContext,
        permission,
        indexGroup,
        mapping,
        settings,
        context
      )
  }

  /**
    * Necessary fields to create/update a SPARQL projection.
    */
  final case class SparqlProjectionFields(
      id: Option[Iri] = None,
      query: SparqlConstructQuery,
      resourceSchemas: Set[Iri] = Set.empty,
      resourceTypes: Set[Iri] = Set.empty,
      resourceTag: Option[UserTag] = None,
      includeDeprecated: Boolean = false,
      includeMetadata: Boolean = false,
      permission: Permission = permissions.query
  ) extends CompositeViewProjectionFields {
    override def tpe: ProjectionType = SparqlProjectionType

    override def toProjection(uuid: UUID, generatedId: Iri, indexingRev: Int): CompositeViewProjection =
      SparqlProjection(
        id.getOrElse(generatedId),
        uuid,
        indexingRev,
        query,
        resourceSchemas,
        resourceTypes,
        resourceTag,
        includeMetadata,
        includeDeprecated,
        permission
      )
  }

  @nowarn("cat=unused")
  implicit final val projectionEncoder: Encoder.AsObject[CompositeViewProjectionFields] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val config: Configuration = Configuration(
      transformMemberNames = {
        case "id"  => keywords.id
        case other => other
      },
      transformConstructorNames = {
        case "ElasticSearchProjectionFields" => ProjectionType.ElasticSearchProjectionType.toString
        case "SparqlProjectionFields"        => ProjectionType.SparqlProjectionType.toString
        case other                           => other
      },
      useDefaults = false,
      discriminator = Some(keywords.tpe),
      strictDecoding = false
    )
    deriveConfiguredEncoder[CompositeViewProjectionFields]
  }

  @nowarn("cat=unused")
  implicit final val projectionLdDecoder: JsonLdDecoder[CompositeViewProjectionFields] = {

    val ctx = Configuration.default.context
      .addAliasIdType("ElasticSearchProjectionFields", ElasticSearchProjectionType.tpe)
      .addAliasIdType("SparqlProjectionFields", SparqlProjectionType.tpe)

    implicit val cfg: Configuration = Configuration.default.copy(context = ctx)
    deriveConfigJsonLdDecoder[CompositeViewProjectionFields]
  }

}
