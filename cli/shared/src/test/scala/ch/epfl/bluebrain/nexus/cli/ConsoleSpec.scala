package ch.epfl.bluebrain.nexus.cli

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.Console.TestConsole
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import fs2.concurrent.Queue
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ConsoleSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "A TestConsole" should {
    val stdQueue             = Queue.unbounded[IO, String].unsafeRunSync()
    val errQueue             = Queue.unbounded[IO, String].unsafeRunSync()
    val console: Console[IO] = new TestConsole[IO](stdQueue, errQueue)

    "print to the standard output" in {
      forAll(0 until 10) { i =>
        console.println(s"Printing STDout line $i").unsafeRunSync()
        stdQueue.tryDequeue1.unsafeRunSync().value shouldEqual s"Printing STDout line $i"
      }
      stdQueue.tryDequeue1.unsafeRunSync() shouldEqual None
    }

    "print to the standard error" in {
      forAll(0 until 10) { i =>
        console.printlnErr(s"Printing STDerr line $i").unsafeRunSync()
        errQueue.tryDequeue1.unsafeRunSync().value shouldEqual s"Printing STDerr line $i"
      }
      errQueue.tryDequeue1.unsafeRunSync() shouldEqual None
    }
  }

}
