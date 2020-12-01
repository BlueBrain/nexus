package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import io.circe.{Decoder, Encoder}

import scala.util.Try

/**
  * A resource reference.
  */
sealed trait ResourceRef extends Product with Serializable {

  /**
    * @return the reference identifier as an iri
    */
  def iri: Iri

  /**
    * @return the original iri
    */
  def original: Iri

  override def toString: String = original.toString
}

object ResourceRef {

  /**
    * An unannotated reference.
    *
    * @param iri the reference identifier as an iri
    */
  final case class Latest(iri: Iri) extends ResourceRef {
    override def original: Iri = iri
  }

  /**
    * A reference annotated with a revision.
    *
    * @param original the original iri
    * @param iri      the reference identifier as an iri (without the tag or rev query parameter)
    * @param rev      the reference revision
    */
  final case class Revision(original: Iri, iri: Iri, rev: Long) extends ResourceRef

  /**
    * A reference annotated with a tag.
    *
    * @param original the original iri
    * @param iri      the reference identifier as an iri (without the tag or rev query parameter)
    * @param tag      the reference tag
    */
  final case class Tag(original: Iri, iri: Iri, tag: Label) extends ResourceRef

  /**
    * Creates a [[ResourceRef]] from the passed ''iri''
    */
  final def apply(iri: Iri): ResourceRef = {

    def extractTagRev(map: Query): Option[Either[Label, Long]] = {
      def rev = map.get("rev").flatMap(s => Try(s.toLong).filter(_ > 0).toOption)
      def tag = map.get("tag").flatMap(s => Option.when(s.nonEmpty)(s)).flatMap(Label(_).toOption)
      rev.map(Right.apply) orElse tag.map(Left.apply)
    }
    extractTagRev(iri.query()) match {
      case Some(Right(rev)) => Revision(iri, iri.removeQueryParams("tag", "rev"), rev)
      case Some(Left(tag))  => Tag(iri, iri.removeQueryParams("tag", "rev"), tag)
      case _                => Latest(iri)
    }
  }

  implicit val resourceRefEncoder: Encoder[ResourceRef]  = Encoder.encodeString.contramap(_.toString)
  implicit val resourceRefDecoder: Decoder[ResourceRef]  = Iri.iriDecoder.map(apply)
  implicit val jsonLdDecoder: JsonLdDecoder[ResourceRef] = JsonLdDecoder.iriJsonLdDecoder.map(apply)

}
