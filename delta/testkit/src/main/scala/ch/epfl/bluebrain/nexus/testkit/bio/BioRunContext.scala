package ch.epfl.bluebrain.nexus.testkit.bio

import monix.execution.Scheduler

trait BioRunContext {
  implicit protected val scheduler: Scheduler = Scheduler.global
}
