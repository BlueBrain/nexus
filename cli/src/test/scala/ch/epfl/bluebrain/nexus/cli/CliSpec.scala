package ch.epfl.bluebrain.nexus.cli

import cats.effect.{ExitCode, IO}
import ch.epfl.bluebrain.nexus.cli.dummies.TestConsole

import scala.io.Source

class CliSpec extends AbstractCliSpec {

  "A CLI" should {
    "show the default config" in { (cli: Cli[IO], console: TestConsole[IO]) =>
      for {
        code     <- cli.command(assemble("config show"))
        expected = linesOf("cli/config-show.txt").mkString("\n").trim
        lines    <- console.stdQueue.dequeue1
        _        = lines.trim shouldEqual expected.trim
        _        = code shouldEqual ExitCode.Success
      } yield ()
    }
    "show the default help" in { (cli: Cli[IO], console: TestConsole[IO]) =>
      for {
        code     <- cli.command(assemble("--help"))
        expected = linesOf("cli/help-main.txt").mkString("\n").trim
        lines    <- console.stdQueue.dequeue1
        _        = lines.trim shouldEqual expected.trim
        _        = code shouldEqual ExitCode.Success
      } yield ()
    }
  }

  def linesOf(resourcePath: String): List[String] =
    Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(resourcePath)).getLines().toList

  def assemble(string: String): List[String] =
    string.split(" ").toList

}
