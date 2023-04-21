package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import akka.http.scaladsl.model.Uri
import cats.Order
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.{CrossProjectSourceFields, ProjectSourceFields, RemoteProjectSourceFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.SourceType._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveDefaultJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PipeChain
import io.circe.{Encoder, Json}

import java.util.UUID
import scala.annotation.nowarn

/**
  * A source for [[CompositeViewValue]].
  */
sealed trait CompositeViewSource extends Product with Serializable {

  /**
    * @return
    *   the id of the source.
    */
  def id: Iri

  /**
    * @return
    *   the uuid of the source
    */
  def uuid: UUID

  /**
    * @return
    *   the set of schemas considered for indexing; empty implies all
    */
  def resourceSchemas: Set[Iri]

  /**
    * @return
    *   the set of resource types considered for indexing; empty implies all
    */
  def resourceTypes: Set[Iri]

  /**
    * @return
    *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field
    *   are indexed with the corresponding revision
    */
  def resourceTag: Option[UserTag]

  /**
    * @return
    *   whether to consider deprecated resources for indexing
    */
  def includeDeprecated: Boolean

  /**
    * @return
    *   the type of the source
    */
  def tpe: SourceType

  /**
    * Translates the source into a [[PipeChain]]
    */
  def pipeChain: Option[PipeChain] =
    PipeChain(resourceSchemas, resourceTypes, includeMetadata = true, includeDeprecated = includeDeprecated)

  /**
    * @return
    *   the [[CompositeViewSource]] projected as [[CompositeViewSourceFields]]
    */
  def toFields: CompositeViewSourceFields
}

object CompositeViewSource {

  /**
    * A source for the current project.
    *
    * @param id
    *   the id of the source.
    * @param uuid
    *   the uuid of the source.
    * @param resourceSchemas
    *   the set of schemas considered for indexing; empty implies all
    * @param resourceTypes
    *   the set of resource types considered for indexing; empty implies all
    * @param resourceTag
    *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field
    *   are indexed with the corresponding revision
    * @param includeDeprecated
    *   whether to consider deprecated resources for indexing
    */
  final case class ProjectSource(
      id: Iri,
      uuid: UUID,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[UserTag],
      includeDeprecated: Boolean
  ) extends CompositeViewSource {

    override def tpe: SourceType = ProjectSourceType

    override def toFields: CompositeViewSourceFields =
      ProjectSourceFields(
        Some(id),
        resourceSchemas,
        resourceTypes,
        resourceTag,
        includeDeprecated
      )
  }

  /**
    * A cross project source.
    *
    * @param id
    *   the id of the source.
    * @param uuid
    *   the uuid of the source.
    * @param resourceSchemas
    *   the set of schemas considered for indexing; empty implies all
    * @param resourceTypes
    *   the set of resource types considered for indexing; empty implies all
    * @param resourceTag
    *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field
    *   are indexed with the corresponding revision
    * @param includeDeprecated
    *   whether to consider deprecated resources for indexing
    * @param project
    *   the project to which source refers to
    * @param identities
    *   the identities used to access the project
    */
  final case class CrossProjectSource(
      id: Iri,
      uuid: UUID,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[UserTag],
      includeDeprecated: Boolean,
      project: ProjectRef,
      identities: Set[Identity]
  ) extends CompositeViewSource {

    override def tpe: SourceType = CrossProjectSourceType

    override def toFields: CompositeViewSourceFields =
      CrossProjectSourceFields(
        Some(id),
        project,
        identities,
        resourceSchemas,
        resourceTypes,
        resourceTag,
        includeDeprecated
      )
  }

  /**
    * A remote project source
    *
    * @param id
    *   the id of the source
    * @param uuid
    *   the uuid of the source.
    * @param resourceSchemas
    *   the set of schemas considered for indexing; empty implies all
    * @param resourceTypes
    *   the set of resource types considered for indexing; empty implies all
    * @param resourceTag
    *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field
    *   are indexed with the corresponding revision
    * @param includeDeprecated
    *   whether to consider deprecated resources for indexing
    * @param endpoint
    *   the endpoint used to access the source
    * @param token
    *   the optional access token used to connect to the endpoint
    */
  final case class RemoteProjectSource(
      id: Iri,
      uuid: UUID,
      resourceSchemas: Set[Iri],
      resourceTypes: Set[Iri],
      resourceTag: Option[UserTag],
      includeDeprecated: Boolean,
      project: ProjectRef,
      endpoint: Uri,
      token: Option[AccessToken]
  ) extends CompositeViewSource {

    override def tpe: SourceType = RemoteProjectSourceType

    override def toFields: CompositeViewSourceFields =
      RemoteProjectSourceFields(
        Some(id),
        project,
        endpoint,
        token.map(_.value),
        resourceTypes,
        resourceTypes,
        resourceTag,
        includeDeprecated
      )
  }

  final case class AccessToken(value: Secret[String])

  @nowarn("cat=unused")
  implicit private val accessTokenEncoder: Encoder[AccessToken] = Encoder.instance(_ => Json.Null)

  @nowarn("cat=unused")
  implicit final def sourceEncoder(implicit base: BaseUri): Encoder.AsObject[CompositeViewSource] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val config: Configuration = Configuration(
      transformMemberNames = {
        case "id"  => keywords.id
        case other => other
      },
      transformConstructorNames = {
        case "ProjectSource"       => SourceType.ProjectSourceType.toString
        case "CrossProjectSource"  => SourceType.CrossProjectSourceType.toString
        case "RemoteProjectSource" => SourceType.RemoteProjectSourceType.toString
        case other                 => other
      },
      useDefaults = false,
      discriminator = Some(keywords.tpe),
      strictDecoding = false
    )
    deriveConfiguredEncoder[CompositeViewSource]
  }

  @nowarn("cat=unused")
  implicit final val sourceLdDecoder: JsonLdDecoder[CompositeViewSource] = {
    implicit val identityLdDecoder: JsonLdDecoder[Identity]       = deriveDefaultJsonLdDecoder[Identity]
    implicit val accessTokenLdDecoder: JsonLdDecoder[AccessToken] = deriveDefaultJsonLdDecoder[AccessToken]
    deriveDefaultJsonLdDecoder[CompositeViewSource]
  }

  implicit final def compositeViewSourceOrdering[A <: CompositeViewSource]: Ordering[A] =
    Ordering.by(_.id)

  implicit final def compositeViewSourceOrder[A <: CompositeViewSource]: Order[A] =
    Order.fromOrdering
}
