package ch.epfl.bluebrain.nexus.cli

import java.nio.file.{Files, Path}
import java.util.regex.Pattern.quote

import cats.effect.{ExitCode, IO}
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.dummies.TestConsole

class CliSpec extends AbstractCliSpec {

  "A CLI" should {
    "show the default config" in { (cli: Cli[IO], console: TestConsole[IO], cfg: AppConfig) =>
      for {
        code        <- cli.command(assemble("config show"))
        replacements = Map(
                         quote("{postgres-offset-file}") -> cfg.postgres.offsetFile.toString,
                         quote("{influx-offset-file}")   -> cfg.influx.offsetFile.toString
                       )
        expected     = contentOf("cli/config-show.txt", replacements)
        lines       <- console.stdQueue.dequeue1
        _            = lines.trim shouldEqual expected.trim
        _            = code shouldEqual ExitCode.Success
      } yield ()
    }
    "show the default help" in { (cli: Cli[IO], console: TestConsole[IO]) =>
      for {
        code    <- cli.command(assemble("--help"))
        expected = contentOf("cli/help-main.txt")
        lines   <- console.stdQueue.dequeue1
        _        = lines.trim shouldEqual expected.trim
        _        = code shouldEqual ExitCode.Success
      } yield ()
    }
  }

  override def copyConfigs: IO[(Path, Path, Path)] =
    IO {
      val parent       = Files.createTempDirectory(".nexus")
      val envFile      = parent.resolve("env.conf")
      val postgresFile = parent.resolve("postgres.conf")
      val influxFile   = parent.resolve("influx.conf")
      Files.copy(getClass.getClassLoader.getResourceAsStream("env.conf"), envFile)
      Files.copy(getClass.getClassLoader.getResourceAsStream("postgres-noprojects.conf"), postgresFile)
      Files.copy(getClass.getClassLoader.getResourceAsStream("influx-noprojects.conf"), influxFile)
      (envFile, postgresFile, influxFile)
    }

  def assemble(string: String): List[String] =
    string.split(" ").toList

}
