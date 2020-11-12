package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import java.time.Instant

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * Enumeration of Resolver event types.
  */
sealed trait ResolverEvent extends Event {

  /**
    * @return the resolver identifier
    */
  def id: Iri

  /**
    * @return the project where the resolver belongs to
    */
  def project: ProjectRef

}

object ResolverEvent {

  /**
    * Event for the creation of a InProjectResolver
    *
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param priority  resolution priority when attempting to find a resource
    * @param rev       the resolver revision
    * @param instant   the instant this event was created
    * @param subject   the subject which created this event
    */
  final case class InProjectResolverCreated(
      id: Iri,
      project: ProjectRef,
      priority: Priority,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  /**
    * Event for the modification of an existing InProjectResolver
    *
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param priority  resolution priority when attempting to find a resource
    * @param rev       the last known revision of the resolver
    * @param instant   the instant this event was created
    * @param subject   the subject which created this event
    */
  final case class InProjectResolverUpdated(
      id: Iri,
      project: ProjectRef,
      priority: Priority,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  /**
    * Event for the creation of a CrossProjectResolver
    *
    * @param id            the resolver identifier
    * @param project       the project the resolver belongs to
    * @param resourceTypes the resource types that will be accessible through this resolver
    *                      if empty, no restriction on resource type will be applied
    * @param projects      references to projects where the resolver will attempt to access
    *                      resources
    * @param identities    identities allowed to use this resolver
    * @param priority      resolution priority when attempting to find a resource
    * @param instant       the instant this event was created
    * @param subject       the subject which created this event
    */
  final case class CrossProjectResolverCreated(
      id: Iri,
      project: ProjectRef,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identities: Set[Identity],
      priority: Priority,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  /**
    * Event for the modification of an existing CrossProjectResolver
    * @param id            the resolver identifier
    * @param project       the project the resolver belongs to
    * @param resourceTypes the resource types that will be accessible through this resolver
    *                      if empty, no restriction on resource type will be applied
    * @param projects      references to projects where the resolver will attempt to access
    *                      resources
    * @param identities    identities allowed to use this resolver
    * @param priority      resolution priority when attempting to find a resource
    * @param rev           the last known revision of the resolver
    * @param instant       the instant this event was created
    * @param subject       the subject creating this event
    */
  final case class CrossProjectResolverUpdated(
      id: Iri,
      project: ProjectRef,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identities: Set[Identity],
      priority: Priority,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  /**
    * Event for the deprecation of a resolver
    * @param id      the resolver identifier
    * @param project the project the resolver belongs to
    * @param rev     the last known revision of the resolver
    * @param instant the instant this event was created
    * @param subject the subject creating this event
    */
  final case class ResolverDeprecated(id: Iri, project: ProjectRef, rev: Long, instant: Instant, subject: Subject)
      extends ResolverEvent

}
