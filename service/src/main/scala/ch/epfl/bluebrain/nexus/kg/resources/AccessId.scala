package ch.epfl.bluebrain.nexus.kg.resources

import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test.Randomness._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.urlEncode
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig

object AccessId {

  private val randBase: AbsoluteIri = url"http://notused.com/${genString()}"

  /**
    * Build an access id (the Uri from where to fetch the resource from the API)
    * E.g.: {base}/v1/resources/{account}/{project}/{schemaId}/{resourceId}
    * The {schemaId} and {resourceId} will be shorten when possible using the
    * available prefixMappings.
    *
    * @param resourceId the resource identifier
    * @param schemaId   the schema identifier
    * @param expanded   flag to decide whether or not we return the expanded version of the id
    */
  def apply(resourceId: AbsoluteIri, schemaId: AbsoluteIri, expanded: Boolean = false)(implicit
      project: Project,
      http: HttpConfig
  ): AbsoluteIri = {

    def prefix(resource: String): AbsoluteIri =
      url"${http.publicUri}" + http.prefix + resource + project.organizationLabel + project.label

    def removeBase(iri: AbsoluteIri): Option[String] =
      if (iri.asString.startsWith(project.base.asString) && iri != project.base)
        Some(iri.asString.stripPrefix(project.base.asString))
      else
        None

    def inner(iri: AbsoluteIri): String = {
      lazy val aliases = project.apiMappings.collectFirst {
        case (p, `iri`) => p
      }
      lazy val curies  = project.apiMappings.collectFirst {
        case (p, ns) if iri.asString.startsWith(ns.asString) =>
          s"$p:${urlEncode(iri.asString.stripPrefix(ns.asString))}"
      }
      lazy val base    = removeBase(iri)
      aliases orElse curies orElse base.map(urlEncode) getOrElse urlEncode(iri.asString)
    }

    if (expanded)
      apply(resourceId, schemaId, false)(project.copy(apiMappings = defaultPrefixMapping, base = randBase), http)
    else {
      val resolvedResourceId = inner(resourceId)
      schemaId match {
        case `fileSchemaUri`          => prefix("files") + resolvedResourceId
        case `viewSchemaUri`          => prefix("views") + resolvedResourceId
        case `resolverSchemaUri`      => prefix("resolvers") + resolvedResourceId
        case `shaclSchemaUri`         => prefix("schemas") + resolvedResourceId
        case `storageSchemaUri`       => prefix("storages") + resolvedResourceId
        case `archiveSchemaUri`       => prefix("archives") + resolvedResourceId
        case `unconstrainedSchemaUri` => prefix("resources") + "_" + resolvedResourceId
        case _                        => prefix("resources") + inner(schemaId) + resolvedResourceId
      }
    }
  }
}
