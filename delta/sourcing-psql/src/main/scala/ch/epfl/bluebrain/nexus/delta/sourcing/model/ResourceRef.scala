package ch.epfl.bluebrain.nexus.delta.sourcing.model

import akka.http.scaladsl.model.Uri.Query
import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.{Decoder, Encoder}

/**
  * A resource reference.
  */
sealed trait ResourceRef extends Product with Serializable { self =>

  /**
    * @return
    *   the reference identifier as an iri
    */
  def iri: Iri

  /**
    * @return
    *   the original iri
    */
  def original: Iri

  override def toString: String = original.toString
}

object ResourceRef {

  /**
    * An unannotated reference.
    *
    * @param iri
    *   the reference identifier as an iri
    */
  final case class Latest(iri: Iri) extends ResourceRef {
    override def original: Iri = iri
  }

  object Latest {
    implicit val latestOrder: Order[Latest] = Order.by { latest => latest.iri }
  }

  /**
    * A reference annotated with a revision.
    *
    * @param original
    *   the original iri
    * @param iri
    *   the reference identifier as an iri (without the tag or rev query parameter)
    * @param rev
    *   the reference revision
    */
  final case class Revision(original: Iri, iri: Iri, rev: Int) extends ResourceRef

  object Revision {

    /**
      * Revision constructor helper
      *
      * @param iri
      *   the reference identifier as an iri (without the tag or rev query parameter)
      * @param rev
      *   the reference revision
      */
    final def apply(iri: Iri, rev: Int): Revision =
      Revision(iri"$iri?rev=$rev", iri, rev)

    implicit val resRefRevEncoder: Encoder[ResourceRef.Revision] = Encoder.encodeString.contramap(_.original.toString)

    implicit val resRefRevDecoder: Decoder[ResourceRef.Revision] = Decoder.decodeString.emap { str =>
      val original = iri"$str"
      val iriNoRev = original.removeQueryParams("rev")
      val optRev   = original.query().get("rev").flatMap(_.toIntOption)
      optRev.map(ResourceRef.Revision(original, iriNoRev, _)).toRight("Expected Int value 'rev' query parameter")
    }

    implicit val revisionOrder: Order[Revision] = Order.by { revision => (revision.iri, revision.rev) }
  }

  /**
    * A reference annotated with a tag.
    *
    * @param original
    *   the original iri
    * @param iri
    *   the reference identifier as an iri (without the tag or rev query parameter)
    * @param tag
    *   the reference tag
    */
  final case class Tag(original: Iri, iri: Iri, tag: UserTag) extends ResourceRef

  object Tag {

    /**
      * Revision constructor helper
      *
      * @param iri
      *   the reference identifier as an iri (without the tag or rev query parameter)
      * @param tag
      *   the reference tag
      */
    final def apply(iri: Iri, tag: UserTag): Tag =
      Tag(iri"$iri?tag=$tag", iri, tag)

    implicit val tagOrder: Order[Tag] = Order.by { tag => (tag.iri, tag.tag.value) }
  }

  /**
    * Creates a [[ResourceRef]] from the passed ''iri''
    */
  final def apply(iri: Iri): ResourceRef = {

    def extractTagRev(map: Query): Option[Either[UserTag, Int]] = {
      def rev = map.get("rev").flatMap(s => s.toIntOption.filter(_ > 0))
      def tag = map.get("tag").flatMap(s => Option.when(s.nonEmpty)(s)).flatMap(UserTag(_).toOption)
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

  /**
    * Defines an order instance such as [[Latest]] > [[Tag]] > [[Revision]]
    */
  implicit val resourceRefOrder: Order[ResourceRef] = Order.from {
    case (_: Revision, _: Latest)     => -1
    case (_: Revision, _: Tag)        => -1
    case (r1: Revision, r2: Revision) => Revision.revisionOrder.compare(r1, r2)
    case (_: Tag, _: Latest)          => -1
    case (_: Tag, _: Revision)        => 1
    case (t1: Tag, t2: Tag)           => Tag.tagOrder.compare(t1, t2)
    case (_: Latest, _: Revision)     => 1
    case (_: Latest, _: Tag)          => 1
    case (l1: Latest, l2: Latest)     => Latest.latestOrder.compare(l1, l2)
  }

}
