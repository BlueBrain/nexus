package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

/**
  * Holds information about resources depending on their access
  */
sealed trait ResourceAccess extends Product with Serializable {

  /**
    * @return
    *   the relative access [[Uri]]
    */
  def relativeUri: Uri

  /**
    * @return
    *   the access [[Uri]]
    */
  def uri(implicit base: BaseUri): Uri =
    relativeUri.resolvedAgainst(base.endpoint.finalSlash())
}

object ResourceAccess {

  /**
    * A resource that is not rooted in a project
    */
  final case class RootAccess(relativeUri: Uri, relativeUriShortForm: Uri) extends ResourceAccess

  /**
    * A resource that is rooted in a project
    */
  final case class InProjectAccess(project: ProjectRef, relativeUri: Uri) extends ResourceAccess

  /**
    * A resource that is rooted in a project but not persisted or indexed.
    */
  final case class EphemeralAccess(project: ProjectRef, relativeUri: Uri) extends ResourceAccess

  /**
    * Constructs [[ResourceAccess]] from the passed arguments and an ''id'' that can be compacted based on the project
    * mappings and base.
    *
    * @param resourceTypeSegment
    *   the resource type segment: resolvers, schemas, resources, etc
    * @param projectRef
    *   the project reference
    * @param id
    *   the id that can be compacted
    */
  final def apply(resourceTypeSegment: String, projectRef: ProjectRef, id: Iri): ResourceAccess = {
    val relative = Uri(resourceTypeSegment) / projectRef.organization.value / projectRef.project.value
    InProjectAccess(projectRef, relative / id.toString)
  }

  /**
    * Constructs [[ResourceAccess]] from a relative [[Uri]].
    *
    * @param relative
    *   the relative base [[Uri]]
    */
  final def apply(relative: Uri): ResourceAccess =
    RootAccess(relative, relative)

  /**
    * Resource access for permissions
    */
  val permissions: ResourceAccess =
    apply("permissions")

  /**
    * Resource access for an acl
    */
  def acl(address: AclAddress): ResourceAccess =
    address match {
      case AclAddress.Root                  => apply("acls")
      case AclAddress.Organization(org)     => apply(s"acls/$org")
      case AclAddress.Project(org, project) => apply(s"acls/$org/$project")
    }

  /**
    * Resource access for a realm
    */
  def realm(label: Label): ResourceAccess =
    apply(s"realms/$label")

  /**
    * Resource access for an organization
    */
  def organization(label: Label): ResourceAccess =
    apply(s"orgs/$label")

  /**
    * Resource access for a project
    */
  def project(ref: ProjectRef): ResourceAccess =
    apply(s"projects/$ref")

  /**
    * Resource access for a resource
    */
  def resource(projectRef: ProjectRef, id: Iri): ResourceAccess = {
    val relative = Uri("resources") / projectRef.organization.value / projectRef.project.value
    InProjectAccess(projectRef, relative / "_" / id.toString)
  }

  /**
    * Resource access for a schema
    */
  def schema(ref: ProjectRef, id: Iri): ResourceAccess =
    apply("schemas", ref, id)

  /**
    * Resource access for a resolver
    */
  def resolver(ref: ProjectRef, id: Iri): ResourceAccess =
    apply("resolvers", ref, id)

  def typeHierarchy: ResourceAccess =
    apply("type-hierarchy")

  /**
    * Resource access for ephemeral resources that are scoped to a project.
    */
  def ephemeral(
      resourceTypeSegment: String,
      ref: ProjectRef,
      id: Iri
  ): ResourceAccess = {
    val relative       = Uri(resourceTypeSegment) / ref.organization.value / ref.project.value
    val relativeAccess = relative / id.toString
    EphemeralAccess(ref, relativeAccess)
  }
}
