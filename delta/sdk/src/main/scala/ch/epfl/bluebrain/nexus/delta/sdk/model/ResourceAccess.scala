package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import org.http4s.Uri

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
    base.endpoint.finalSlash.resolve(relativeUri)
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
    * @param project
    *   the project reference
    * @param id
    *   the id that can be compacted
    */
  final def apply(resourceTypeSegment: String, project: ProjectRef, id: Iri): ResourceAccess = {
    val relative = Uri.unsafeFromString(resourceTypeSegment) / project.organization.value / project.project.value
    InProjectAccess(project, relative / id.toString)
  }

  /**
    * Constructs [[ResourceAccess]] from a relative [[Uri]].
    *
    * @param relative
    *   the relative base [[Uri]]
    */
  final def apply(relative: Uri): ResourceAccess =
    RootAccess(relative, relative)

  final def unsafe(relative: String): ResourceAccess =
    apply(Uri.unsafeFromString(relative))

  /**
    * Resource access for permissions
    */
  val permissions: ResourceAccess =
    unsafe("permissions")

  /**
    * Resource access for an acl
    */
  def acl(address: AclAddress): ResourceAccess =
    address match {
      case AclAddress.Root                  => unsafe("acls")
      case AclAddress.Organization(org)     => unsafe(s"acls/$org")
      case AclAddress.Project(org, project) => unsafe(s"acls/$org/$project")
    }

  /**
    * Resource access for a realm
    */
  def realm(label: Label): ResourceAccess =
    unsafe(s"realms/$label")

  /**
    * Resource access for an organization
    */
  def organization(label: Label): ResourceAccess =
    unsafe(s"orgs/$label")

  /**
    * Resource access for a project
    */
  def project(project: ProjectRef): ResourceAccess =
    unsafe(s"projects/$project")

  /**
    * Resource access for a resource
    */
  def resource(project: ProjectRef, id: Iri): ResourceAccess = {
    val relative = Uri.unsafeFromString("resources") / project.organization.value / project.project.value
    InProjectAccess(project, relative / "_" / id.toString)
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
    unsafe("type-hierarchy")

  /**
    * Resource access for ephemeral resources that are scoped to a project.
    */
  def ephemeral(
      resourceTypeSegment: String,
      ref: ProjectRef,
      id: Iri
  ): ResourceAccess = {
    val relative       = Uri.unsafeFromString(resourceTypeSegment) / ref.organization.value / ref.project.value
    val relativeAccess = relative / id.toString
    EphemeralAccess(ref, relativeAccess)
  }
}
