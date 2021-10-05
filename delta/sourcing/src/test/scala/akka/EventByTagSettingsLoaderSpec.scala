package akka

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class EventByTagSettingsLoaderSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with OptionValues {

  "Load" should {
    "get the correct value from the config" in {
      val settings = EventByTagSettingsLoader.load
      settings.cleanUpPersistenceIds.value shouldEqual 2.hours
    }
  }

}
