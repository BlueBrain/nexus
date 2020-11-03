package ch.epfl.bluebrain.nexus.sourcing

import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateResponse._
import monix.bio.IO

package object processor {

  /**
    * Type alias that represents `IO` in an evaluation context.
    */
  type EvaluationIO[Rejection, Event, State] = IO[EvaluationRejection[Rejection], EvaluationSuccess[Event, State]]

}
