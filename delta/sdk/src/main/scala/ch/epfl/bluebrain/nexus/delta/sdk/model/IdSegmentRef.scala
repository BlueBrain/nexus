package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * A segment from the positional API that should be an Id plus its revision/tag information
  */
sealed trait IdSegmentRef extends Product with Serializable { self =>

  /**
    * @return the inner [[IdSegment]]
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

  def apply(id: IdSegment): IdSegmentRef                                = Latest(id)
  def apply(id: IdSegment, rev: Long): IdSegmentRef                     = Revision(id, rev)
  def apply(id: IdSegment, tag: TagLabel): IdSegmentRef                 = Tag(id, tag)
  def fromRevOpt(id: IdSegment, revOpt: Option[Long]): IdSegmentRef     = revOpt.fold(apply(id))(Revision(id, _))
  def fromTagOpt(id: IdSegment, tagOpt: Option[TagLabel]): IdSegmentRef = tagOpt.fold(apply(id))(Tag(id, _))

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
  final case class Tag(value: IdSegment, tag: TagLabel) extends IdSegmentRef

}
