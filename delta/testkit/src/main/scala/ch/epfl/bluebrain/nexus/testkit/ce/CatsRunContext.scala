package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.unsafe.IORuntime

trait CatsRunContext {

  implicit val runtime: IORuntime = IORuntime.global

}
