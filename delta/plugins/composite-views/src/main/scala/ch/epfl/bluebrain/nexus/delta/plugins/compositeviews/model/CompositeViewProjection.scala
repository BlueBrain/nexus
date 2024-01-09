package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.GraphResourceToNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel.IndexGroup
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.GraphResourceToDocument
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing.model.IriFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Operation, PipeChain}
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
    * @return
    *   the indexing revision of the projection
    */
  def indexingRev: IndexingRev

  /**
    * SPARQL query used to create values indexed into the projection.
    */
  def query: SparqlConstructQuery

  /**
    * @return
    *   the schemas to filter by, empty means all
    */
  def resourceSchemas: IriFilter

  /**
    * @return
    *   the resource types to filter by, empty means all
    */
  def resourceTypes: IriFilter

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

  /**
    * Translates the projection into a [[PipeChain]]
    */
  def pipeChain: Option[PipeChain] = PipeChain(resourceSchemas, resourceTypes, includeMetadata, includeDeprecated)

  def transformationPipe(implicit rcr: RemoteContextResolution): Operation.Pipe

  def updateIndexingRev(value: IndexingRev): CompositeViewProjection =
    this match {
      case e: ElasticSearchProjection => e.copy(indexingRev = value)
      case s: SparqlProjection        => s.copy(indexingRev = value)
    }
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
      indexingRev: IndexingRev,
      query: SparqlConstructQuery,
      resourceSchemas: IriFilter,
      resourceTypes: IriFilter,
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      includeContext: Boolean,
      permission: Permission,
      indexGroup: Option[IndexGroup],
      mapping: JsonObject,
      settings: Option[JsonObject] = None,
      context: ContextObject
  ) extends CompositeViewProjection {

    override def tpe: ProjectionType                              = ElasticSearchProjectionType
    override def asSparql: Option[SparqlProjection]               = None
    override def asElasticSearch: Option[ElasticSearchProjection] = Some(this)

    override def transformationPipe(implicit rcr: RemoteContextResolution) =
      new GraphResourceToDocument(context, includeContext)
  }

  /**
    * A Sparql projection for [[CompositeView]].
    */
  final case class SparqlProjection(
      id: Iri,
      uuid: UUID,
      indexingRev: IndexingRev,
      query: SparqlConstructQuery,
      resourceSchemas: IriFilter,
      resourceTypes: IriFilter,
      includeMetadata: Boolean,
      includeDeprecated: Boolean,
      permission: Permission
  ) extends CompositeViewProjection {

    override def tpe: ProjectionType                              = SparqlProjectionType
    override def asSparql: Option[SparqlProjection]               = Some(this)
    override def asElasticSearch: Option[ElasticSearchProjection] = None

    override def transformationPipe(implicit rcr: RemoteContextResolution): Operation.Pipe =
      GraphResourceToNTriples
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
