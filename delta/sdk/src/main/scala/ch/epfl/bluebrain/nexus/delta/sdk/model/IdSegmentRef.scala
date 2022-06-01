package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag

/**
  * A segment from the positional API that should be an Id plus its revision/tag information
  */
sealed trait IdSegmentRef extends Product with Serializable { self =>

  /**
    * @return
    *   the inner [[IdSegment]]
    */
  def value: IdSegment

  /**
    * Converts the current [[IdSegmentRef]] to an [[IdSegmentRef.Revision]]
    */
  def toRev(rev: Long): IdSegmentRef.Revision = IdSegmentRef.Revision(self.value, rev)

  /**
    * Converts the current [[IdSegmentRef]] to an [[IdSegmentRef.Latest]]
    */
  def toLatest: IdSegmentRef.Latest = IdSegmentRef.Latest(self.value)

  def asTag: Option[IdSegmentRef.Tag] = self match {
    case tag: IdSegmentRef.Tag => Some(tag)
    case _                     => None
  }

  def asRev: Option[IdSegmentRef.Revision] = self match {
    case rev: IdSegmentRef.Revision => Some(rev)
    case _                          => None
  }

}

object IdSegmentRef {

  implicit def idSegmentToIdSegmentRef(id: IdSegment): IdSegmentRef = apply(id)
  implicit def iriToIdSegmentRef(iri: Iri): IdSegmentRef            = Latest(iri)
  implicit def stringToIdSegmentRef(string: String): IdSegmentRef   = Latest(string)

  def apply(id: IdSegment): IdSegmentRef                               = Latest(id)
  def apply(id: IdSegment, rev: Long): IdSegmentRef                    = Revision(id, rev)
  def apply(id: IdSegment, tag: UserTag): IdSegmentRef                 = Tag(id, tag)
  def fromRevOpt(id: IdSegment, revOpt: Option[Long]): IdSegmentRef    = revOpt.fold(apply(id))(Revision(id, _))
  def fromTagOpt(id: IdSegment, tagOpt: Option[UserTag]): IdSegmentRef = tagOpt.fold(apply(id))(Tag(id, _))

  /**
    * Converts a [[ResourceRef]] to an [[IdSegmentRef]]
    */
  def apply(ref: ResourceRef): IdSegmentRef =
    ref match {
      case ResourceRef.Latest(iri)           => IdSegmentRef(iri)
      case ResourceRef.Revision(_, iri, rev) => IdSegmentRef(iri, rev)
      case ResourceRef.Tag(_, iri, tag)      => IdSegmentRef(iri, tag)
    }

  /**
    * A segment.
    */
  final case class Latest(value: IdSegment) extends IdSegmentRef

  /**
    * A segment annotated with a rev.
    */
  final case class Revision(value: IdSegment, rev: Long) extends IdSegmentRef

  /**
    * A segment annotated with a tag.
    */
  final case class Tag(value: IdSegment, tag: UserTag) extends IdSegmentRef

}
