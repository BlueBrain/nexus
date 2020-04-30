package ch.epfl.bluebrain.nexus.cli.postgres.cli

import cats.effect.{ExitCode, IO}
import ch.epfl.bluebrain.nexus.cli.Console.TestConsole
import ch.epfl.bluebrain.nexus.cli.postgres.AbstractPostgresSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.io.Source

class CliSpec extends AbstractPostgresSpec {

  "A CLI" should {
    "show the default config" in { (cli: Cli[IO], console: TestConsole[IO]) =>
      {
        for {
          code     <- cli.command(assemble("config show"))
          expected = linesOf("cli/config-show.txt").mkString("\n").trim
          lines    <- console.stdQueue.dequeue1
          _        = lines.trim shouldEqual expected.trim
          _        = code shouldEqual ExitCode.Success
        } yield ()
      }
    }
    "show the default config with the provided token" in { (cli: Cli[IO], console: TestConsole[IO]) =>
      {
        for {
          code     <- cli.command(assemble("config show --token provided"))
          expected = linesOf("cli/config-show-with-token.txt").mkString("\n").trim
          lines    <- console.stdQueue.dequeue1
          _        = lines.trim shouldEqual expected.trim
          _        = code shouldEqual ExitCode.Success
        } yield ()
      }
    }
    "show the default help" in { (cli: Cli[IO], console: TestConsole[IO]) =>
      {
        for {
          code     <- cli.command(assemble("--help"))
          expected = linesOf("cli/help-main.txt").mkString("\n").trim
          lines    <- console.stdQueue.dequeue1
          _        = lines.trim shouldEqual expected.trim
          _        = code shouldEqual ExitCode.Success
        } yield ()
      }
    }
  }

  def linesOf(resourcePath: String): List[String] =
    Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(resourcePath)).getLines().toList

  def assemble(string: String): List[String] =
    string.split(" ").toList

}
