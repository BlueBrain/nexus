package ch.epfl.bluebrain.nexus.sourcingnew

import ch.epfl.bluebrain.nexus.sourcingnew.processor.ProcessorCommand.{EvaluationRejection, EvaluationSuccess}
import monix.bio.IO

package object processor {

  /**
    * Type alias that represents `IO` in an evaluation context.
    */
  type EvaluationIO[Rejection, Event, State] = IO[EvaluationRejection[Rejection], EvaluationSuccess[Event, State]]

}
