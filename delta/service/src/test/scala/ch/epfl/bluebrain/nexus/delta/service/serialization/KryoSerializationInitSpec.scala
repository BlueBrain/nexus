package ch.epfl.bluebrain.nexus.delta.service.serialization

import java.nio.file.Paths

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.serialization.SerializationExtension
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import com.typesafe.config.ConfigFactory
import io.altoo.akka.serialization.kryo.KryoSerializer
import org.apache.jena.iri.{IRI, IRIFactory}
import org.scalatest.TryValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class KryoSerializerInitSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load("akka-test.conf"))
    with AnyWordSpecLike
    with Matchers
    with TryValues
    with TestHelpers
    with IOValues
    with EitherValuable {

  private val serialization                         = SerializationExtension(system)
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed()

  private val expanded = ExpandedJsonLd(jsonContentOf("/kryo/expanded.json")).accepted
  private val graph    = Graph(expanded).rightValue
  private val iri      = iri"http://nexus.example.com/john-doé"

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

  "An Anonymous Graph Kryo serialization" should {
    val expandedJson = jsonContentOf("/kryo/expanded.json").removeAll(keywords.id -> iri)
    val graphNoId    = Graph(ExpandedJsonLd(expandedJson).accepted).rightValue

    "succeed" in {
      // Find the Serializer for it
      val serializer = serialization.findSerializerFor(graphNoId)
      serializer.getClass.equals(classOf[KryoSerializer]) shouldEqual true

      // Check serialization/deserialization
      val serialized = serialization.serialize(graphNoId)
      serialized.isSuccess shouldEqual true

      val deserialized            = serialization.deserialize(serialized.get, graphNoId.getClass)
      deserialized.isSuccess shouldEqual true
      val deserializedGraph       = deserialized.success.value
      deserializedGraph.rootNode shouldBe a[BNode]
      val deserializedGraphWithId = deserializedGraph.replace(deserializedGraph.rootNode, iri)
      deserializedGraphWithId.triples shouldEqual graph.triples
    }
  }

  "An Iri Graph Kryo serialization" should {
    "succeed" in {
      // Find the Serializer for it
      val serializer = serialization.findSerializerFor(graph)
      serializer.getClass.equals(classOf[KryoSerializer]) shouldEqual true

      // Check serialization/deserialization
      val serialized = serialization.serialize(graph)
      serialized.isSuccess shouldEqual true

      val deserialized = serialization.deserialize(serialized.get, graph.getClass)
      deserialized.isSuccess shouldEqual true
      deserialized.success.value shouldEqual graph
    }
  }

  "An Jena Model Kryo serialization" should {

    "succeed" in {

      // Find the Serializer for it
      val serializer = serialization.findSerializerFor(graph.model)
      serializer.getClass.equals(classOf[KryoSerializer]) shouldEqual true

      // Check serialization/deserialization
      val serialized = serialization.serialize(graph.model)
      serialized.isSuccess shouldEqual true

      val deserialized      = serialization.deserialize(serialized.get, graph.model.getClass)
      deserialized.isSuccess shouldEqual true
      val deserializedModel = deserialized.success.value
      Graph.unsafe(graph.rootNode, deserializedModel).triples shouldEqual graph.triples
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
