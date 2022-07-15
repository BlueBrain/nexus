package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * State for an existing in project resolver
  * @param id
  *   the id of the resolver
  * @param project
  *   the project it belongs to
  * @param value
  *   additional fields to configure the resolver
  * @param source
  *   the representation of the resolver as posted by the subject
  * @param tags
  *   the collection of tag aliases
  * @param rev
  *   the current state revision
  * @param deprecated
  *   the current state deprecation status
  * @param createdAt
  *   the instant when the resource was created
  * @param createdBy
  *   the subject that created the resource
  * @param updatedAt
  *   the instant when the resource was last updated
  * @param updatedBy
  *   the subject that last updated the resource
  */
final case class ResolverState(
    id: Iri,
    project: ProjectRef,
    value: ResolverValue,
    source: Json,
    tags: Tags,
    rev: Int,
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  def schema: ResourceRef = Latest(schemas.resolvers)

  override def types: Set[Iri] = value.tpe.types

  def resolver: Resolver = {
    value match {
      case inProjectValue: InProjectValue       =>
        InProjectResolver(
          id = id,
          project = project,
          value = inProjectValue,
          source = source,
          tags = tags
        )
      case crossProjectValue: CrossProjectValue =>
        CrossProjectResolver(
          id = id,
          project = project,
          value = crossProjectValue,
          source = source,
          tags = tags
        )
    }
  }

  def toResource(mappings: ApiMappings, base: ProjectBase): ResolverResource =
    ResourceF(
      id = id,
      uris = ResourceUris.resolver(project, id)(mappings, base),
      rev = rev.toLong,
      types = value.tpe.types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = resolver
    )
}

object ResolverState {
  @nowarn("cat=unused")
  val serializer: Serializer[Iri, ResolverState] = {
    import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.Database._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                      = Serializer.circeConfiguration
    implicit val resolverValueCodec: Codec.AsObject[ResolverValue] = deriveConfiguredCodec[ResolverValue]
    implicit val codec: Codec.AsObject[ResolverState]              = deriveConfiguredCodec[ResolverState]
    Serializer(_.id)
  }
}
