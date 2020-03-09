package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.Paths

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.types.Offset
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class OffsetConfigSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "An Offset Config" should {

    val offsetConfig = OffsetConfig(Some(Offset("b8a93f50-5c75-11ea-beb1-a5eb66b44d1c").value))

    "be created from a passed config file" in {
      val path = Paths.get(getClass().getResource("/offset-test.conf").toURI())
      OffsetConfig(path) shouldEqual Right(offsetConfig)
    }

    "be saved to a file" in {
      val path = Paths.get(s"${genString()}.conf")
      offsetConfig.write[IO](path).unsafeRunSync() shouldEqual Right(())
      path.toFile.exists() shouldEqual true
      OffsetConfig(path) shouldEqual Right(offsetConfig)
      path.toFile.delete()
    }

  }

}
