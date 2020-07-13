package ch.epfl.bluebrain.nexus.kg.serializers

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.kg.TestHelper
import io.altoo.akka.serialization.kryo.KryoSerializer
import org.scalatest.TryValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class KryoSerializerInitSpec
    extends TestKit(ActorSystem("KryoSerializerInitSpec"))
    with AnyWordSpecLike
    with Matchers
    with TryValues
    with TestHelper {
  private val serialization = SerializationExtension(system)

  "A Path Kryo serialization" should {
    "succeed" in {
      val path = Paths.get("resources/application.conf")

      // Find the Serializer for it
      val serializer = serialization.findSerializerFor(path)
      serializer.getClass.equals(classOf[KryoSerializer]) shouldEqual true

      // Check serialization/deserialization
      val serialized = serialization.serialize(path)
      serialized.isSuccess shouldEqual true

      val deserialized = serialization.deserialize(serialized.get, path.getClass)
      deserialized.isSuccess shouldEqual true
      deserialized.success.value shouldEqual path
    }
  }

}
