package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

/**
  * Holds information about the different access Uri of a resource
  */
sealed trait ResourceUris extends Product with Serializable {

  /**
    * @return the relative access [[Uri]]
    */
  private[sdk] def relativeAccessUri: Uri

  /**
    * @return the relative access [[Uri]] in a short form
    */
  private[sdk] def relativeAccessUriShortForm: Uri

  /**
    * @return the access [[Uri]]
    */
  def accessUri(implicit base: BaseUri): Uri =
    relativeAccessUri.resolvedAgainst(base.endpoint.finalSlash())

  /**
    * @return the access [[Uri]] in a short form
    */
  def accessUriShortForm(implicit base: BaseUri): Uri =
    relativeAccessUriShortForm.resolvedAgainst(base.endpoint.finalSlash())

  /**
    * @return the incoming [[Uri]]
    */
  def incoming(implicit base: BaseUri): Option[Uri]

  /**
    * @return the outgoing [[Uri]]
    */
  def outgoing(implicit base: BaseUri): Option[Uri]

  /**
    * @return the incoming [[Uri]] in a short form
    */
  def incomingShortForm(implicit base: BaseUri): Option[Uri]

  /**
    * @return the outgoing [[Uri]] in a short form
    */
  def outgoingShortForm(implicit base: BaseUri): Option[Uri]

}

object ResourceUris {

  final private case class WithNavigation(relativeAccessUri: Uri, relativeAccessUriShortForm: Uri)
      extends ResourceUris {
    override def incoming(implicit base: BaseUri): Option[Uri]          = Some(accessUri / "incoming")
    override def outgoing(implicit base: BaseUri): Option[Uri]          = Some(accessUri / "outgoing")
    override def incomingShortForm(implicit base: BaseUri): Option[Uri] = Some(accessUriShortForm / "incoming")
    override def outgoingShortForm(implicit base: BaseUri): Option[Uri] = Some(accessUriShortForm / "outgoing")
  }

  final private case class WithoutNavigation(relativeAccessUri: Uri, relativeAccessUriShortForm: Uri)
      extends ResourceUris {
    override def incoming(implicit base: BaseUri): Option[Uri]          = None
    override def outgoing(implicit base: BaseUri): Option[Uri]          = None
    override def incomingShortForm(implicit base: BaseUri): Option[Uri] = None
    override def outgoingShortForm(implicit base: BaseUri): Option[Uri] = None
  }

  /**
    * Constructs [[ResourceUris]] from the passed arguments and an ''id'' that can be
    * compacted based on the project mappings and base.
    *
    * @param resourceTypeSegment the resource type segment: resolvers, schemas, resources, etc
    * @param projectRef          the project reference
    * @param id                  the id that can be compacted
    */
  final def apply(resourceTypeSegment: String, projectRef: ProjectRef, id: Iri)(
      mappings: ApiMappings,
      base: ProjectBase
  ): ResourceUris = {
    val ctx               = context(base, mappings + ApiMappings.default)
    val relative          = Uri(resourceTypeSegment) / projectRef.organization.value / projectRef.project.value
    val relativeShortForm = relative / ctx.compact(id, useVocab = false)
    WithNavigation(relative / id.toString, relativeShortForm)
  }

  /**
    * Constructs [[ResourceUris]] from a relative [[Uri]].
    *
    * @param relative the relative base [[Uri]]
    */
  final def apply(relative: Uri): ResourceUris =
    WithoutNavigation(relative, relative)

  /**
    * Constructs [[ResourceUris]] from the passed arguments.
    * The ''id'' and ''schema'' can be compacted based on the project mappings and base.
    *
    * @param resourceTypeSegment the resource type segment: resolvers, schemas, resources, etc
    * @param projectRef          the project reference
    * @param id                  the id that can be compacted
    * @param schema              the schema reference that can be compacted
    */
  private def apply(resourceTypeSegment: String, projectRef: ProjectRef, schema: ResourceRef, id: Iri)(
      mappings: ApiMappings,
      base: ProjectBase
  ): ResourceUris = {
    val ctx               = context(base, mappings + ApiMappings.default)
    val relative          = Uri(resourceTypeSegment) / projectRef.organization.value / projectRef.project.value
    val relativeShortForm = relative / ctx.compact(schema.iri, useVocab = false) / ctx.compact(id, useVocab = false)
    WithNavigation(relative / schema.toString / id.toString, relativeShortForm)
  }

  /**
    * Resource uris for permissions
    */
  val permissions: ResourceUris =
    apply("permissions")

  /**
    * Resource uris for an acl
    */
  def acl(address: AclAddress): ResourceUris =
    address match {
      case AclAddress.Root                  => apply("acls")
      case AclAddress.Organization(org)     => apply(s"acls/$org")
      case AclAddress.Project(org, project) => apply(s"acls/$org/$project")
    }

  /**
    * Resource uris for a realm
    */
  def realm(label: Label): ResourceUris =
    apply(s"realms/$label")

  /**
    * Resource uris for an organization
    */
  def organization(label: Label): ResourceUris =
    apply(s"orgs/$label")

  /**
    * Resource uris for a project
    */
  def project(ref: ProjectRef): ResourceUris =
    apply(s"projects/$ref")

  /**
    * Resource uris for a resource
    */
  def resource(ref: ProjectRef, id: Iri, schema: ResourceRef)(mappings: ApiMappings, base: ProjectBase): ResourceUris =
    apply("resources", ref, schema, id)(mappings, base)

  /**
    * Resource uris for a schema
    */
  def schema(ref: ProjectRef, id: Iri)(mappings: ApiMappings, base: ProjectBase): ResourceUris =
    apply("schemas", ref, id)(mappings, base)

  /**
    * Resource uris for a resolver
    */
  def resolver(ref: ProjectRef, id: Iri)(mappings: ApiMappings, base: ProjectBase): ResourceUris =
    apply("resolvers", ref, id)(mappings, base)

  private def context(base: ProjectBase, mappings: ApiMappings): JsonLdContext =
    JsonLdContext(
      ContextValue.empty,
      base = Some(base.iri),
      prefixMappings = mappings.prefixMappings,
      aliases = mappings.aliases
    )

}
