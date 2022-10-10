package ch.epfl.bluebrain.nexus.delta.sdk.instances

import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait ProjectRefInstances {

  implicit final val projectRefIriEncoder: IriEncoder[ProjectRef] = new IriEncoder[ProjectRef] {
    override def apply(value: ProjectRef)(implicit base: BaseUri): Iri =
      ResourceUris.project(value).accessUriShortForm.toIri
  }

  implicit final val projectRefOrder: Order[ProjectRef] = Order.by { projectRef =>
    (projectRef.project.value, projectRef.organization.value)
  }

}
