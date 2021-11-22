package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import monix.bio.Task
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PipeConfigSpec extends AnyWordSpec with Matchers with EitherValuable {

  private val foo        = Pipe.withoutConfig("foo", _ => Task.none)
  private val bar        = Pipe.withoutConfig("bar", _ => Task.none)
  private val anotherBar = Pipe.withoutConfig("bar", _ => Task.none)

  "PipeConfig" should {
    "load up fine when every pipe name is unique" in {
      PipeConfig(Set(foo, bar)).rightValue shouldEqual PipeConfig(Map("foo" -> foo, "bar" -> bar))
    }

    "raise an error when a pipe name is defined several times" in {
      PipeConfig(Set(foo, bar, anotherBar)).leftValue shouldEqual "'bar' is defined multiple times."
    }
  }

}
