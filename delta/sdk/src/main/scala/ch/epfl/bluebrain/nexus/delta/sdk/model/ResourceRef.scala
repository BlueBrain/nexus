package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
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
    * @param iri      the reference identifier as an iri
    * @param rev      the reference revision
    */
  final case class Revision(original: Iri, iri: Iri, rev: Long) extends ResourceRef

  /**
    * A reference annotated with a tag.
    *
    * @param original the original iri
    * @param iri      the reference identifier as an iri
    * @param tag      the reference tag
    */
  final case class Tag(original: Iri, iri: Iri, tag: String) extends ResourceRef

  private def firstAs[A](seq: Seq[String], f: String => Option[A]): Option[A] =
    seq.singleEntry.flatMap(f)

  /**
    * Creates a [[ResourceRef]] from the passed ''iri''
    */
  final def apply(iri: Iri): ResourceRef = {

    def extractTagRev(map: Iri.Query): Option[Either[String, Long]] = {
      def rev = map.get("rev").flatMap(seq => firstAs(seq, s => Try(s.toLong).filter(_ > 0).toOption))
      def tag = map.get("tag").flatMap(seq => firstAs(seq, s => Option.when(s.nonEmpty)(s)))
      rev.map(Right.apply) orElse tag.map(Left.apply)
    }
    extractTagRev(iri.query()) match {
      case Some(Right(rev)) => Revision(iri, iri.removeQueryParams("tag", "rev"), rev)
      case Some(Left(tag))  => Tag(iri, iri.removeQueryParams("tag", "rev"), tag)
      case _                => Latest(iri)
    }
  }

  implicit val resourceRefEncoder: Encoder[ResourceRef] = Encoder.encodeString.contramap(_.toString)
  implicit val resourceRefDecoder: Decoder[ResourceRef] = Iri.iriDecoder.map(apply)

}
