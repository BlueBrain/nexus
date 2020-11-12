package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * Enumeration of Resolver command types.
  */
sealed trait ResolverCommand extends Product with Serializable {

  /**
    * @return the project where the resolver belongs to
    */
  def project: ProjectRef

  /**
    * @return the resolver identifier
    */
  def id: Iri
}

object ResolverCommand {

  /**
    * Command to create a new resolver
    * @param id                the resolver identifier
    * @param project           the project the resolver belongs to
    * @param `type`            type of the resolver (can't be updated)
    * @param priority          resolution priority when attempting to find a resource
    * @param crossProjectSetup additional setup for a cross-project resolver
    */
  final case class CreateResolver(
      id: Iri,
      project: ProjectRef,
      `type`: ResolverType,
      priority: Priority,
      crossProjectSetup: Option[CrossProjectSetup]
  ) extends ResolverCommand

  /**
    * Command to update an existing resolver
    * @param id                the resolver identifier
    * @param project           the project the resolver belongs to
    * @param `type`            type of the resolver (can't be updated)
    * @param priority          resolution priority when attempting to find a resource
    * @param crossProjectSetup additional setup for a cross-project resolver
    * @param rev               the last known revision of the resolver
    */
  final case class UpdateResolver(
      id: Iri,
      project: ProjectRef,
      `type`: ResolverType,
      priority: Priority,
      crossProjectSetup: Option[CrossProjectSetup],
      rev: Long
  ) extends ResolverCommand

  /**
    * Command to tag a resolver
    *
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''tagRev''
    * @param rev       the last known revision of the resolver
    */
  final case class TagResolver(id: Iri, project: ProjectRef, targetRev: Long, tag: Label, rev: Long)
      extends ResolverCommand

  /**
    * Command to deprecate a resolver
    * @param id      the resolver identifier
    * @param project the project the resolver belongs to
    * @param rev     the last known revision of the resolver
    */
  final case class DeprecateResolver(id: Iri, project: ProjectRef, rev: Long) extends ResolverCommand

}
