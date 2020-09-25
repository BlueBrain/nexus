package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * A resource reference.
  */
sealed trait ResourceRef extends Product with Serializable {

  /**
    * @return the reference identifier as an iri
    */
  def iri: Iri
}

object ResourceRef {

  /**
    * An unannotated reference.
    *
    * @param iri the reference identifier as an iri
    */
  final case class Latest(iri: Iri) extends ResourceRef

  /**
    * A reference annotated with a revision.
    *
    * @param iri the reference identifier as an iri
    * @param rev the reference revision
    */
  final case class Revision(iri: Iri, rev: Long) extends ResourceRef

  /**
    * A reference annotated with a tag.
    *
    * @param iri the reference identifier as an iri
    * @param tag the reference tag
    */
  final case class Tag(iri: Iri, tag: String) extends ResourceRef

}
