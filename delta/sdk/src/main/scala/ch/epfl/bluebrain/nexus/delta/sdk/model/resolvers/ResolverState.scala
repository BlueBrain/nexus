package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import java.time.Instant

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, ResourceF, ResourceRef}

/**
  * Enumeration of Resolver state types
  */
sealed trait ResolverState extends Product with Serializable {

  /**
    * @return the schema reference that resolvers conforms to
    */
  final def schema: ResourceRef = Latest(schemas.resolvers)

  /**
    * @return the collection of known types of resolvers resources
    */
  final def types: Set[Iri] = Set(nxv.Schema)

  /**
    * Converts the state into a resource representation.
    */
  def toResource: Option[ResolverResource]

}

object ResolverState {

  /**
    * Initial resolver state.
    */
  final case object Initial extends ResolverState {
    override val toResource: Option[ResolverResource] = None
  }

  /**
    * State for an existing resolver
    */
  sealed trait Current extends ResolverState {

    /**
      * @return the id of the resolver
      */
    def id: Iri

    /**
      * @return the current state deprecation status
      */
    def deprecated: Boolean

    /**
      * @return the current state revision
      */
    def rev: Long

  }

  /**
    * State for an existing in project resolver
    * @param id         the id of the resolver
    * @param project    the project it belongs to
    * @param priority   the resolution priority when attempting to find a resource
    * @param rev        the current state revision
    * @param deprecated the current state deprecation status
    * @param createdAt  the instant when the resource was created
    * @param createdBy  the subject that created the resource
    * @param updatedAt  the instant when the resource was last updated
    * @param updatedBy  the subject that last updated the resource
    */
  final case class InProjectCurrent(
      id: Iri,
      project: ProjectRef,
      priority: Priority,
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends Current {

    def resolver: InProjectResolver =
      InProjectResolver(
        id = id,
        project = project,
        priority = priority
      )

    override def toResource: Option[ResolverResource] =
      Some(
        ResourceF(
          id = AccessUrl.resolver(project, id)(_).iri,
          accessUrl = AccessUrl.resolver(project, id)(_).value,
          rev = rev,
          types = types,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = resolver
        )
      )
  }

  /**
    * State for an existing cross project resolver
    *
    * @param id            the id of the resolver
    * @param project       the project it belongs to
    * @param resourceTypes the resource types that will be accessible through this resolver
    *                      if empty, no restriction on resource type will be applied
    * @param projects      references to projects where the resolver will attempt to access
    *                      resources
    * @param identities    the identities allowed to use this resolver
    * @param priority      the resolution priority when attempting to find a resource
    * @param rev           the current state revision
    * @param deprecated    the current state deprecation status
    * @param createdAt     the instant when the resource was created
    * @param createdBy     the subject that created the resource
    * @param updatedAt     the instant when the resource was last updated
    * @param updatedBy     the subject that last updated the resource
    */
  final case class CrossProjectCurrent(
      id: Iri,
      project: ProjectRef,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identities: Set[Identity],
      priority: Priority,
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends Current {

    def resolver: CrossProjectResolver =
      CrossProjectResolver(
        id = id,
        project = project,
        resourceTypes = resourceTypes,
        projects = projects,
        identities = identities,
        priority = priority
      )

    override def toResource: Option[ResolverResource] =
      Some(
        ResourceF(
          id = AccessUrl.resolver(project, id)(_).iri,
          accessUrl = AccessUrl.resolver(project, id)(_).value,
          rev = rev,
          types = types,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = resolver
        )
      )
  }

}
