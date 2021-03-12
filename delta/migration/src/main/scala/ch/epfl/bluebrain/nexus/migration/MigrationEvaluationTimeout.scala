package ch.epfl.bluebrain.nexus.migration

import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.EvaluationTimeout

final case class MigrationEvaluationTimeout(err: EvaluationTimeout[_]) extends Exception {
  override def fillInStackTrace(): MigrationEvaluationTimeout = this
}
