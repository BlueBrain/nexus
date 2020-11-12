package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * Enumeration of Resolver rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ResolverRejection(val reason: String) extends Product with Serializable

object ResolverRejection {

  /**
    * Rejection returned when a subject intends to retrieve a resolver at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends ResolverRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when attempting to create a resolver with an id that already exists.
    *
    * @param id      the resolver identifier
    * @param project the project it belongs to
    */
  final case class ResolverAlreadyExists(id: Iri, project: ProjectRef)
      extends ResolverRejection(s"Resolver '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to update a resolver with an id that doesn't exist.
    *
    * @param id      the resolver identifier
    * @param project the project it belongs to
    */
  final case class ResolverNotFound(id: Iri, project: ProjectRef)
      extends ResolverRejection(s"Resolver '$id' not found in project $project.")

  /**
    * Rejection returned when attempting to create a resolver with an id that already exists.
    *
    * @param id the resolver identifier
    */
  final case class DifferentResolverType(id: Iri, found: String, expected: String)
      extends ResolverRejection(s"Resolver '$id' is of type ''$found'' and can't be updated to be a ''$expected'' .")

  /**
    * Rejection returned when no identities has been provided
    */
  final case object NoIdentities extends ResolverRejection(s"At least one identity of the caller must be provided")

  /**
    * Rejection return when the logged caller does not have one of the provided identities
    */
  final case class InvalidIdentities(missingIdentities: Set[Identity])
      extends ResolverRejection(
        s"The caller doesn't have some of the provided identities: ${missingIdentities.mkString(",")}"
      )

  /**
    * Rejection returned when a subject intends to perform an operation on the current resolver, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends ResolverRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the resolver may have been updated since last seen."
      )

  /**
    * Rejection returned when attempting to update/deprecate a resolver that is already deprecated.
    *
    * @param id the resolver identifier
    */
  final case class ResolverIsDeprecated(id: Iri) extends ResolverRejection(s"Resolver '$id' is deprecated.")

  /**
    * Rejection returned when the project or the organization the resolver belongs to has been deprecated
    */
  final case class ReadOnlyProject(project: ProjectRef)
      extends ResolverRejection(
        s"The project with label '$project' is read-only as its organization or itself have been deprecated."
      )

}
