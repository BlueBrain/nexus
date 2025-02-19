package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

/**
  * Holds information about resources depending on theirs scopes
  */
sealed trait ResourceScope extends Product with Serializable {

  /**
    * @return
    *   the relative access [[Uri]]
    */
  def relativeAccessUri: Uri

  /**
    * @return
    *   the access [[Uri]]
    */
  def accessUri(implicit base: BaseUri): Uri =
    relativeAccessUri.resolvedAgainst(base.endpoint.finalSlash())
}

object ResourceScope {

  /**
    * A resource that is not rooted in a project
    */
  final case class GlobalResourceF(relativeAccessUri: Uri, relativeAccessUriShortForm: Uri) extends ResourceScope

  /**
    * A resource that is rooted in a project
    */
  final case class ScopedResourceF(project: ProjectRef, relativeAccessUri: Uri) extends ResourceScope {
    def incoming(implicit base: BaseUri): Uri = accessUri / "incoming"
    def outgoing(implicit base: BaseUri): Uri = accessUri / "outgoing"
  }

  /**
    * A resource that is rooted in a project but not persisted or indexed.
    */
  final case class EphemeralResourceF(project: ProjectRef, relativeAccessUri: Uri) extends ResourceScope

  /**
    * Constructs [[ResourceScope]] from the passed arguments and an ''id'' that can be compacted based on the project
    * mappings and base.
    *
    * @param resourceTypeSegment
    *   the resource type segment: resolvers, schemas, resources, etc
    * @param projectRef
    *   the project reference
    * @param id
    *   the id that can be compacted
    */
  final def apply(resourceTypeSegment: String, projectRef: ProjectRef, id: Iri): ResourceScope = {
    val relative = Uri(resourceTypeSegment) / projectRef.organization.value / projectRef.project.value
    ScopedResourceF(projectRef, relative / id.toString)
  }

  /**
    * Constructs [[ResourceScope]] from a relative [[Uri]].
    *
    * @param relative
    *   the relative base [[Uri]]
    */
  final def apply(relative: Uri): ResourceScope =
    GlobalResourceF(relative, relative)

  /**
    * Resource scope for permissions
    */
  val permissions: ResourceScope =
    apply("permissions")

  /**
    * Resource scope for an acl
    */
  def acl(address: AclAddress): ResourceScope =
    address match {
      case AclAddress.Root                  => apply("acls")
      case AclAddress.Organization(org)     => apply(s"acls/$org")
      case AclAddress.Project(org, project) => apply(s"acls/$org/$project")
    }

  /**
    * Resource scope for a realm
    */
  def realm(label: Label): ResourceScope =
    apply(s"realms/$label")

  /**
    * Resource scope for an organization
    */
  def organization(label: Label): ResourceScope =
    apply(s"orgs/$label")

  /**
    * Resource scope for a project
    */
  def project(ref: ProjectRef): ResourceScope =
    apply(s"projects/$ref")

  /**
    * Resource scope for a resource
    */
  def resource(projectRef: ProjectRef, id: Iri): ResourceScope = {
    val relative = Uri("resources") / projectRef.organization.value / projectRef.project.value
    ScopedResourceF(projectRef, relative / "_" / id.toString)
  }

  /**
    * Resource scope for a schema
    */
  def schema(ref: ProjectRef, id: Iri): ResourceScope =
    apply("schemas", ref, id)

  /**
    * Resource scope for a resolver
    */
  def resolver(ref: ProjectRef, id: Iri): ResourceScope =
    apply("resolvers", ref, id)

  def typeHierarchy: ResourceScope =
    apply("type-hierarchy")

  /**
    * Resource scope for ephemeral resources that are scoped to a project.
    */
  def ephemeral(
      resourceTypeSegment: String,
      ref: ProjectRef,
      id: Iri
  ): ResourceScope = {
    val relative       = Uri(resourceTypeSegment) / ref.organization.value / ref.project.value
    val relativeAccess = relative / id.toString
    EphemeralResourceF(ref, relativeAccess)
  }
}
