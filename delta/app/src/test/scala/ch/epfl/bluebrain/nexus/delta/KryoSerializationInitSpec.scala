package ch.epfl.bluebrain.nexus.delta

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import io.altoo.akka.serialization.kryo.KryoSerializer
import org.apache.jena.iri.{IRI, IRIFactory}
import org.scalatest.TryValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class KryoSerializerInitSpec
    extends TestKit(ActorSystem("KryoSerializerInitSpec", ConfigFactory.load("default.conf")))
    with AnyWordSpecLike
    with Matchers
    with TryValues {
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

  "An Jena IRI Kryo serialization" should {
    val iriFactory = IRIFactory.iriImplementation()

    "succeed" in {
      val iri: IRI = iriFactory.create("http://example.com")

      // Find the Serializer for it
      val serializer = serialization.findSerializerFor(iri)
      serializer.getClass.equals(classOf[KryoSerializer]) shouldEqual true

      // Check serialization/deserialization
      val serialized = serialization.serialize(iri)
      serialized.isSuccess shouldEqual true

      val deserialized = serialization.deserialize(serialized.get, iri.getClass)
      deserialized.isSuccess shouldEqual true
      deserialized.success.value shouldEqual iri
    }
  }

}
