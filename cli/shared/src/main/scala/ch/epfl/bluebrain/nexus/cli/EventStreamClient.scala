package ch.epfl.bluebrain.nexus.cli

import ch.epfl.bluebrain.nexus.cli.types.{EventEnvelope, Label, Offset}
import fs2.Stream

trait EventStreamClient[F[_]] {

  /**
    * Fetch the event stream for all Nexus resources.
    *
    * @param lastEventId the optional starting event offset
    */
  def apply(lastEventId: Option[Offset]): F[Stream[F, EventEnvelope]]

  /**
    * Fetch the event stream for all Nexus resources in the passed ''organization''.
    *
    * @param organization the organization label
    * @param lastEventId the optional starting event offset
    */
  def apply(organization: Label, lastEventId: Option[Offset]): F[Stream[F, EventEnvelope]]

  /**
    * Fetch the event stream for all Nexus resources in the passed ''organization'' and ''project''.
    *
    * @param organization the organization label
    * @param lastEventId the optional starting event offset
    */
  def apply(organization: Label, project: Label, lastEventId: Option[Offset]): F[Stream[F, EventEnvelope]]
}
