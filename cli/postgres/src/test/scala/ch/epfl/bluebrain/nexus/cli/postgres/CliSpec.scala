package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.{ExitCode, IO}
import ch.epfl.bluebrain.nexus.cli.postgres.cli.Cli
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

class CliSpec extends AbstractPostgresSpec {

  "A CLI" should {
    "show the default config" in {
      Cli[IO](command("config show")).unsafeRunSync() shouldEqual ExitCode.Success
    }
    "show the default config with the provided token" in {
      Cli[IO](command("config show --token provided")).unsafeRunSync() shouldEqual ExitCode.Success
    }
  }

}
