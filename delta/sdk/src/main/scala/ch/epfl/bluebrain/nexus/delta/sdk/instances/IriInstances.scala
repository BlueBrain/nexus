package ch.epfl.bluebrain.nexus.delta.sdk.instances

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import doobie.{Get, Put}

trait IriInstances {

  implicit val iriGet: Get[Iri] = Get[String].temap(Iri(_))
  implicit val iriPut: Put[Iri] = Put[String].contramap(_.toString)

}
