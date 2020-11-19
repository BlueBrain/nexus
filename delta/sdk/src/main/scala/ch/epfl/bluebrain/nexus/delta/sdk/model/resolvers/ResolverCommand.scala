package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
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

  /**
    * @return the identity associated to this command
    */
  def subject: Subject
}

object ResolverCommand {

  /**
    * Command to create a new resolver
    * @param id                the resolver identifier
    * @param project           the project the resolver belongs to
    * @param value             additional fields to configure the resolver
    * @param caller            the caller associated to this command
    */
  final case class CreateResolver(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      caller: Caller
  ) extends ResolverCommand {
    override def subject: Subject = caller.subject
  }

  /**
    * Command to update an existing resolver
    * @param id                the resolver identifier
    * @param project           the project the resolver belongs to
    * @param value             additional fields to configure the resolver
    * @param rev               the last known revision of the resolver
    * @param caller            the caller associated to this command
    */
  final case class UpdateResolver(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      rev: Long,
      caller: Caller
  ) extends ResolverCommand {
    override def subject: Subject = caller.subject
  }

  /**
    * Command to tag a resolver
    *
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''tagRev''
    * @param rev       the last known revision of the resolver
    * @param subject   the identity associated to this command
    */
  final case class TagResolver(id: Iri, project: ProjectRef, targetRev: Long, tag: Label, rev: Long, subject: Subject)
      extends ResolverCommand

  /**
    * Command to deprecate a resolver
    * @param id      the resolver identifier
    * @param project the project the resolver belongs to
    * @param rev     the last known revision of the resolver
    * @param subject the identity associated to this command
    */
  final case class DeprecateResolver(id: Iri, project: ProjectRef, rev: Long, subject: Subject) extends ResolverCommand

}
