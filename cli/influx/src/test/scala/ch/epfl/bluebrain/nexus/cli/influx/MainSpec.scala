package ch.epfl.bluebrain.nexus.cli.influx

import cats.effect.ExitCode
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MainSpec extends AnyWordSpecLike with Matchers with ScalaFutures {

  "A Main" should {

    "return successful exit code" in {
      Main.run(List("example")).runToFuture.futureValue shouldEqual ExitCode.Success
    }
  }

}
