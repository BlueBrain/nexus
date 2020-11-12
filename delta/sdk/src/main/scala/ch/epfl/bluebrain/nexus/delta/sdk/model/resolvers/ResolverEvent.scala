package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, Label}

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
    * Event for the creation of a resolver
    *
    * @param id                the resolver identifier
    * @param project           the project the resolver belongs to
    * @param `type`            type of the resolver (can't be updated)
    * @param priority          resolution priority when attempting to find a resource
    * @param crossProjectSetup additional setup for a cross-project resolver    * @param rev       the resolver revision
    * @param instant           the instant this event was created
    * @param subject           the subject which created this event
    */
  final case class ResolverCreated(
      id: Iri,
      project: ProjectRef,
      `type`: ResolverType,
      priority: Priority,
      crossProjectSetup: Option[CrossProjectSetup],
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  /**
    * Event for the modification of an existing resolver
    *
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param `type`            type of the resolver (can't be updated)
    * @param priority          resolution priority when attempting to find a resource
    * @param crossProjectSetup additional setup for a cross-project resolver
    * @param rev       the last known revision of the resolver
    * @param instant   the instant this event was created
    * @param subject   the subject which created this event
    */
  final case class ResolverUpdated(
      id: Iri,
      project: ProjectRef,
      `type`: ResolverType,
      priority: Priority,
      crossProjectSetup: Option[CrossProjectSetup],
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  /**
    * Event for to tag a resolver
    *
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''tagRev''
    * @param rev       the last known revision of the resolver
    * @param instant   the instant this event was created
    * @param subject   the subject creating this event
    */
  final case class ResolverTagAdded(
      id: Iri,
      project: ProjectRef,
      targetRev: Long,
      tag: Label,
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
