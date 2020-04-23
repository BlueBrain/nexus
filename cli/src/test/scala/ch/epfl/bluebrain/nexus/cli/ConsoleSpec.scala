package ch.epfl.bluebrain.nexus.cli

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.dummies.TestConsole

class ConsoleSpec extends AbstractCliSpec {

  "A TestConsole" should {
    "record the println" in { (tc: TestConsole[IO], c: Console[IO]) =>
      for {
        _    <- c.println("line")
        line <- tc.stdQueue.dequeue1
        _    = line shouldEqual "line"
      } yield ()
    }
  }

}
