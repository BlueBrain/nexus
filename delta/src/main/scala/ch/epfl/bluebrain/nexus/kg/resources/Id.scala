package ch.epfl.bluebrain.nexus.kg.resources

import cats.Show
import ch.epfl.bluebrain.nexus.kg.resources.Ref.Latest
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

/**
  * The unique id of a resource.
  *
  * @param parent the parent reference of the resource identified by this id
  * @param value  the unique identifier for the resource within the referenced parent
  */
final case class Id[P](parent: P, value: AbsoluteIri) {

  /**
    * @return a reference to this id
    */
  def ref: Ref =
    Latest(value)
}

object Id {
  implicit final def idShow[P](implicit P: Show[P], I: Show[AbsoluteIri]): Show[Id[P]] =
    Show.show {
      case Id(parent, value) => s"${P.show(parent)} / ${I.show(value)}"
    }
}
