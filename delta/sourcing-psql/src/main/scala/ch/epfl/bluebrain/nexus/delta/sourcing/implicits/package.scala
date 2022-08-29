package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import doobie.Get
import doobie.Put

package object implicits {

  implicit final val iriGet: Get[Iri] = Get[String].temap(Iri(_))
  implicit final val iriPut: Put[Iri] = Put[String].contramap(_.toString)

}
