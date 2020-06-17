package ch.epfl.bluebrain.nexus.kg

import akka.cluster.ddata.LWWRegister.Clock

/**
  * A value with an attached revision corresponding to the value.
  *
  * @param rev   the value revision
  * @param value the value
  */
final case class RevisionedValue[A](rev: Long, value: A)

object RevisionedValue {

  def revisionedValueClock[A]: Clock[RevisionedValue[A]] =
    (_: Long, value: RevisionedValue[A]) => value.rev
}
