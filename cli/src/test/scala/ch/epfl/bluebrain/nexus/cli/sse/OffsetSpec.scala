package ch.epfl.bluebrain.nexus.cli.sse

import java.nio.file.{Files, Path}
import java.util.UUID

import cats.effect.{Blocker, ContextShift, IO}
import ch.epfl.bluebrain.nexus.cli.Console
import ch.epfl.bluebrain.nexus.cli.dummies.TestConsole
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext

class OffsetSpec extends AnyWordSpecLike with Matchers with OptionValues {

  implicit protected val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit private val blocker: Blocker       = Blocker.liftExecutionContext(ExecutionContext.global)
  implicit private val console: Console[IO]   = TestConsole[IO].unsafeRunSync()

  abstract class Ctx {
    protected val uuid: UUID = UUID.randomUUID
    protected val file: Path = Files.createTempFile("offset", ".conf")
  }

  "An offset" should {
    "be loaded from configuration" in new Ctx {
      Files.writeString(file, uuid.toString)
      (for {
        offset <- Offset.load(file)
        _       = offset.value shouldEqual Offset(uuid)
      } yield Files.deleteIfExists(file)).unsafeRunSync()
    }

    "be loaded from configuration but failed to convert to UUID" in new Ctx {
      Files.writeString(file, "not-an-uuid")
      (for {
        offset <- Offset.load(file)
        _       = offset shouldEqual None
      } yield Files.deleteIfExists(file)).unsafeRunSync()
    }

    "be written to file" in new Ctx {
      val offset = Offset(UUID.randomUUID())
      (for {
        _ <- offset.write(file)
        _  = Files.readString(file) shouldEqual offset.value.toString
      } yield Files.deleteIfExists(file)).unsafeRunSync()
    }
  }
}
