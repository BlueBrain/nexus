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
  private[model] def relativeAccessUri: Uri

  /**
    * @return the relative access [[Uri]] in a short form
    */
  private[model] def relativeAccessUriShortForm: Uri

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
}

object ResourceUris {

  /**
    * A resource that is not rooted in a project
    */
  final case class RootResourceUris(relativeAccessUri: Uri, relativeAccessUriShortForm: Uri) extends ResourceUris

  /**
    * A resource that is rooted in a project
    */
  final case class ResourceInProjectUris(
      projectRef: ProjectRef,
      relativeAccessUri: Uri,
      relativeAccessUriShortForm: Uri
  ) extends ResourceUris {
    def incoming(implicit base: BaseUri): Uri          = accessUri / "incoming"
    def outgoing(implicit base: BaseUri): Uri          = accessUri / "outgoing"
    def incomingShortForm(implicit base: BaseUri): Uri = accessUriShortForm / "incoming"
    def outgoingShortForm(implicit base: BaseUri): Uri = accessUriShortForm / "outgoing"
    def project(implicit base: BaseUri): Uri           = ResourceUris.project(projectRef).accessUri

  }

  /**
    * A resource that is rooted in a project and a schema: the system resources that are validated against a schema
    */
  final case class ResourceInProjectAndSchemaUris(
      projectRef: ProjectRef,
      schemaProjectRef: ProjectRef,
      relativeAccessUri: Uri,
      relativeAccessUriShortForm: Uri
  ) extends ResourceUris {
    def incoming(implicit base: BaseUri): Uri          = accessUri / "incoming"
    def outgoing(implicit base: BaseUri): Uri          = accessUri / "outgoing"
    def incomingShortForm(implicit base: BaseUri): Uri = accessUriShortForm / "incoming"
    def outgoingShortForm(implicit base: BaseUri): Uri = accessUriShortForm / "outgoing"
    def project(implicit base: BaseUri): Uri           = ResourceUris.project(projectRef).accessUri
    def schemaProject(implicit base: BaseUri): Uri     = ResourceUris.project(schemaProjectRef).accessUri
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
    ResourceInProjectUris(projectRef, relative / id.toString, relativeShortForm)
  }

  /**
    * Constructs [[ResourceUris]] from a relative [[Uri]].
    *
    * @param relative the relative base [[Uri]]
    */
  final def apply(relative: Uri): ResourceUris =
    RootResourceUris(relative, relative)

  /**
    * Constructs [[ResourceUris]] from the passed arguments.
    * The ''id'' and ''schema'' can be compacted based on the project mappings and base.
    *
    * @param resourceTypeSegment the resource type segment: resolvers, schemas, resources, etc
    * @param projectRef          the project reference
    * @param schemaProject       the schema project reference
    * @param id                  the id that can be compacted
    * @param schema              the schema reference that can be compacted
    */
  private def apply(
      resourceTypeSegment: String,
      projectRef: ProjectRef,
      schemaProject: ProjectRef,
      schema: ResourceRef,
      id: Iri
  )(
      mappings: ApiMappings,
      base: ProjectBase
  ): ResourceUris = {
    val ctx               = context(base, mappings + ApiMappings.default)
    val relative          = Uri(resourceTypeSegment) / projectRef.organization.value / projectRef.project.value
    val relativeShortForm = relative / ctx.compact(schema.iri, useVocab = false) / ctx.compact(id, useVocab = false)
    ResourceInProjectAndSchemaUris(
      projectRef,
      schemaProject,
      relative / schema.toString / id.toString,
      relativeShortForm
    )
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
  def resource(projectRef: ProjectRef, schemaProject: ProjectRef, id: Iri, schema: ResourceRef)(
      mappings: ApiMappings,
      base: ProjectBase
  ): ResourceUris =
    apply("resources", projectRef, schemaProject, schema, id)(mappings, base)

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
