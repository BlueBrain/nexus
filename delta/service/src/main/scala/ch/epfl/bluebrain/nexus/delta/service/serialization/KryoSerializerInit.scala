package ch.epfl.bluebrain.nexus.delta.service.serialization

import akka.actor.ExtendedActorSystem
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.service.serialization.KryoSerializerInit._
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo
import org.apache.jena.graph.Factory.createDefaultGraph
import org.apache.jena.iri.{IRI, IRIFactory}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.{Lang, RDFParser, RDFWriter}

import java.nio.file.Path

class KryoSerializerInit extends DefaultKryoInitializer {

  override def postInit(kryo: ScalaKryo, system: ExtendedActorSystem): Unit = {
    super.postInit(kryo, system)
    kryo.addDefaultSerializer(classOf[IRI], classOf[IRISerializer])
    kryo.register(classOf[IRI], new IRISerializer)

    kryo.addDefaultSerializer(classOf[Model], classOf[ModelSerializer])
    kryo.register(classOf[Model], new ModelSerializer)

    kryo.addDefaultSerializer(classOf[Path], classOf[PathSerializer])
    kryo.register(classOf[Path], new PathSerializer)

    kryo.addDefaultSerializer(classOf[Graph], classOf[GraphSerializer])
    kryo.register(classOf[Graph], new GraphSerializer)
    ()
  }
}

object KryoSerializerInit {

  private val iriFactory = IRIFactory.iriImplementation()

  private[serialization] class GraphSerializer extends Serializer[Graph] {
    private val fakeIRI = iriFactory.create("http://localhost/6ff2c90a-2bc3-4fd5-bf0b-bf2d563f28a7")
    private val fakeIri = Iri.unsafe(fakeIRI.toString)

    override def write(kryo: Kryo, output: Output, graph: Graph): Unit =
      graph.rootNode match {
        case Iri(value) =>
          kryo.writeObject(output, graph.model)
          kryo.writeObject(output, value)

        case bNode: BNode =>
          kryo.writeObject(output, graph.replace(bNode, fakeIri).model)
          kryo.writeObject(output, fakeIRI)
      }

    override def read(kryo: Kryo, input: Input, `type`: Class[_ <: Graph]): Graph = {
      val model = kryo.readObject(input, classOf[Model])
      kryo.readObject(input, classOf[IRI]) match {
        case `fakeIRI` =>
          val bNode = BNode.random
          Graph.unsafe(bNode, model).replace(fakeIri, bNode)
        case other     =>
          Graph.unsafe(Iri.unsafe(other.toString), model)
      }
    }
  }

  private[serialization] class ModelSerializer extends Serializer[Model] {

    override def write(kryo: Kryo, output: Output, model: Model): Unit =
      output.writeString(RDFWriter.create.lang(Lang.NTRIPLES).source(model).asString())

    override def read(kryo: Kryo, input: Input, `type`: Class[_ <: Model]): Model = {
      val model = ModelFactory.createModelForGraph(createDefaultGraph())
      RDFParser.create().fromString(input.readString()).lang(Lang.NTRIPLES).parse(model)
      model
    }
  }

  private[serialization] class IRISerializer extends Serializer[IRI] {

    override def write(kryo: Kryo, output: Output, iri: IRI): Unit =
      output.writeString(iri.toString)

    override def read(kryo: Kryo, input: Input, `type`: Class[_ <: IRI]): IRI =
      iriFactory.create(input.readString())
  }

  private[serialization] class PathSerializer extends Serializer[Path] {

    override def write(kryo: Kryo, output: Output, path: Path): Unit =
      output.writeString(path.toString)

    override def read(kryo: Kryo, input: Input, `type`: Class[_ <: Path]): Path =
      Path.of(input.readString())
  }
}
