package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{AccessToken, CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.SourceType._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import java.util.UUID
import scala.annotation.nowarn

/**
  * Necessary fields to create/update a composite view source.
  */
sealed trait CompositeViewSourceFields {

  /**
    * @return
    *   the id of the source
    */
  def id: Option[Iri]

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
    *   the source type
    */
  def tpe: SourceType

  /**
    * Transform from [[CompositeViewSourceFields]] to [[CompositeViewSource]]
    */
  def toSource(uuid: UUID, generatedId: Iri): CompositeViewSource
}

object CompositeViewSourceFields {

  /**
    * Necessary fields to create/update a project source.
    */
  final case class ProjectSourceFields(
      id: Option[Iri] = None,
      resourceSchemas: Set[Iri] = Set.empty,
      resourceTypes: Set[Iri] = Set.empty,
      resourceTag: Option[UserTag] = None,
      includeDeprecated: Boolean = false
  ) extends CompositeViewSourceFields {
    override def tpe: SourceType = ProjectSourceType

    override def toSource(uuid: UUID, generatedId: Iri): CompositeViewSource =
      ProjectSource(
        id.getOrElse(generatedId),
        uuid,
        resourceSchemas,
        resourceTypes,
        resourceTag,
        includeDeprecated
      )
  }

  /**
    * Necessary fields to create/update a cross project source.
    */
  final case class CrossProjectSourceFields(
      id: Option[Iri] = None,
      project: ProjectRef,
      identities: Set[Identity],
      resourceSchemas: Set[Iri] = Set.empty,
      resourceTypes: Set[Iri] = Set.empty,
      resourceTag: Option[UserTag] = None,
      includeDeprecated: Boolean = false
  ) extends CompositeViewSourceFields {
    override def tpe: SourceType = CrossProjectSourceType

    override def toSource(uuid: UUID, generatedId: Iri): CompositeViewSource = CrossProjectSource(
      id.getOrElse(generatedId),
      uuid,
      resourceSchemas,
      resourceTypes,
      resourceTag,
      includeDeprecated,
      project,
      identities
    )
  }

  /**
    * Necessary fields to create/update a remote project source.
    */
  final case class RemoteProjectSourceFields(
      id: Option[Iri] = None,
      project: ProjectRef,
      endpoint: Uri,
      token: Option[Secret[String]] = None,
      resourceSchemas: Set[Iri] = Set.empty,
      resourceTypes: Set[Iri] = Set.empty,
      resourceTag: Option[UserTag] = None,
      includeDeprecated: Boolean = false
  ) extends CompositeViewSourceFields {
    override def tpe: SourceType = RemoteProjectSourceType

    override def toSource(uuid: UUID, generatedId: Iri): CompositeViewSource = RemoteProjectSource(
      id.getOrElse(generatedId),
      uuid,
      resourceSchemas,
      resourceTypes,
      resourceTag,
      includeDeprecated,
      project,
      endpoint,
      token.map(AccessToken)
    )
  }

  @nowarn("cat=unused")
  implicit private val accessTokenEncoder: Encoder[AccessToken] = deriveEncoder[AccessToken]

  @nowarn("cat=unused")
  implicit final def sourceEncoder(implicit base: BaseUri): Encoder.AsObject[CompositeViewSourceFields] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto._
    implicit val config: Configuration = Configuration(
      transformMemberNames = {
        case "id"  => keywords.id
        case other => other
      },
      transformConstructorNames = {
        case "ProjectSourceFields"       => SourceType.ProjectSourceType.toString
        case "CrossProjectSourceFields"  => SourceType.CrossProjectSourceType.toString
        case "RemoteProjectSourceFields" => SourceType.RemoteProjectSourceType.toString
        case other                       => other
      },
      useDefaults = false,
      discriminator = Some(keywords.tpe),
      strictDecoding = false
    )
    deriveConfiguredEncoder[CompositeViewSourceFields]
  }

  @nowarn("cat=unused")
  implicit final val sourceLdDecoder: JsonLdDecoder[CompositeViewSourceFields] = {

    val ctx = Configuration.default.context
      .addAliasIdType("ProjectSourceFields", SourceType.ProjectSourceType.tpe)
      .addAliasIdType("CrossProjectSourceFields", SourceType.CrossProjectSourceType.tpe)
      .addAliasIdType("RemoteProjectSourceFields", SourceType.RemoteProjectSourceType.tpe)

    implicit val cfg: Configuration                       = Configuration.default.copy(context = ctx)
    implicit val identityDecoder: JsonLdDecoder[Identity] = deriveConfigJsonLdDecoder[Identity]
      .or(deriveConfigJsonLdDecoder[User].asInstanceOf[JsonLdDecoder[Identity]])
      .or(deriveConfigJsonLdDecoder[Group].asInstanceOf[JsonLdDecoder[Identity]])
      .or(deriveConfigJsonLdDecoder[Authenticated].asInstanceOf[JsonLdDecoder[Identity]])

    deriveConfigJsonLdDecoder[CompositeViewSourceFields]
  }
}
