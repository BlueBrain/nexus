package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

/**
  * The enumeration access url for each resource type
  */
sealed trait AccessUrl extends Product with Serializable {

  /**
    * @return the Url to access the resource
    */
  def value: Uri

  /**
    * @return the Iri to access the resource
    */
  def iri: Iri = value.toIri

  /**
    * @return the short form url to access the resource
    */
  def shortForm(mappings: ApiMappings): Uri

  override def toString: String = value.toString
}

object AccessUrl {

  final private case class Fixed(value: Uri) extends AccessUrl {
    override def shortForm(mappings: ApiMappings): Uri = value
  }

  final private case class CompactableId(endpoint: Uri, id: Iri) extends AccessUrl {
    override val value: Uri = endpoint / id.toString

    override def shortForm(mappings: ApiMappings): Uri = {
      val ctx = context(mappings + ApiMappings.default)
      endpoint / ctx.compact(id, useVocab = false)
    }
  }

  final private case class CompactableSchemaAndId(endpoint: Uri, schema: ResourceRef, id: Iri) extends AccessUrl {
    override val value: Uri = endpoint / schema.toString / id.toString

    override def shortForm(mappings: ApiMappings): Uri = {
      val ctx = context(mappings + ApiMappings.default)
      endpoint / ctx.compact(schema.iri, useVocab = false) / ctx.compact(id, useVocab = false)
    }
  }

  /**
    * Access Url for permissions
    */
  def permissions(implicit base: BaseUri): AccessUrl =
    Fixed(base.endpoint / "permissions")

  /**
    * Access Url for an acl
    */
  def acl(address: AclAddress)(implicit base: BaseUri): AccessUrl =
    address match {
      case AclAddress.Root                  => Fixed(base.endpoint / "acls")
      case AclAddress.Organization(org)     => Fixed(base.endpoint / "acls" / org.value)
      case AclAddress.Project(org, project) => Fixed(base.endpoint / "acls" / org.value / project.value)
    }

  /**
    * Access Url for a realm
    */
  def realm(label: Label)(implicit base: BaseUri): AccessUrl =
    Fixed(base.endpoint / "realms" / label.toString)

  /**
    * Access Url for an organization
    */
  def organization(label: Label)(implicit base: BaseUri): AccessUrl =
    Fixed(base.endpoint / "orgs" / label.toString)

  /**
    * Access Url for a project
    */
  def project(ref: ProjectRef)(implicit base: BaseUri): AccessUrl =
    Fixed(base.endpoint / "projects" / ref.organization.toString / ref.project.toString)

  /**
    * Access Url for a resource
    */
  def resource(ref: ProjectRef, id: Iri, schema: ResourceRef)(implicit base: BaseUri): AccessUrl =
    CompactableSchemaAndId(base.endpoint / "resources" / ref.organization.value / ref.project.value, schema, id)

  /**
    * Access Url for a schema
    */
  def schema(ref: ProjectRef, id: Iri)(implicit base: BaseUri): AccessUrl =
    CompactableId(base.endpoint / "schemas" / ref.organization.value / ref.project.value, id)

  /**
    * Access Url for a resolver
    */
  def resolver(ref: ProjectRef, id: Iri)(implicit base: BaseUri): AccessUrl =
    CompactableId(base.endpoint / "resolvers" / ref.organization.value / ref.project.value, id)

  private def context(mappings: ApiMappings): JsonLdContext =
    JsonLdContext(ContextValue.empty, prefixMappings = mappings.prefixMappings, aliases = mappings.aliases)

}
