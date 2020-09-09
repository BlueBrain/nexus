package ch.epfl.bluebrain.nexus.delta.sdk.model

import org.apache.jena.iri.IRI

/**
  * A resource reference.
  */
sealed trait ResourceRef extends Product with Serializable {

  /**
    * @return the reference identifier as an iri
    */
  def iri: IRI
}

object ResourceRef {

  /**
    * An unannotated reference.
    *
    * @param iri the reference identifier as an iri
    */
  final case class Latest(iri: IRI) extends ResourceRef

  /**
    * A reference annotated with a revision.
    *
    * @param iri the reference identifier as an iri
    * @param rev the reference revision
    */
  final case class Revision(iri: IRI, rev: Long) extends ResourceRef

  /**
    * A reference annotated with a tag.
    *
    * @param iri the reference identifier as an iri
    * @param tag the reference tag
    */
  final case class Tag(iri: IRI, tag: String) extends ResourceRef

}
