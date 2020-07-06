package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.kg.resources.{AccessId, Resource}
import ch.epfl.bluebrain.nexus.service.config.AppConfig.HttpConfig

/**
  * Represents a resource uri that needs redirection
  *
  * @param value the redirect uri
  */
final case class ResourceRedirect(value: Uri)

object ResourceRedirect {

  /**
    * Constructs a [[ResourceRedirect]] using the _self uri from the provided resource
    */
  def apply(resource: Resource)(implicit project: ProjectResource, config: HttpConfig): ResourceRedirect =
    ResourceRedirect(AccessId(resource.id.value, resource.schema.iri).asAkka)
}
