package ch.epfl.bluebrain.nexus.delta.service.serialization

import akka.actor.ExtendedActorSystem
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.service.serialization.KryoSerializerInit._
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo
import org.apache.jena.iri.{IRI, IRIFactory}
import org.apache.jena.query.DatasetFactory
import org.apache.jena.riot.{Lang, RDFParser, RDFWriter}
import org.apache.jena.sparql.core.DatasetGraph

import java.nio.file.Path

class KryoSerializerInit extends DefaultKryoInitializer {

  override def postInit(kryo: ScalaKryo, system: ExtendedActorSystem): Unit = {
    super.postInit(kryo, system)
    kryo.addDefaultSerializer(classOf[IRI], classOf[IRISerializer])
    kryo.register(classOf[IRI], new IRISerializer)

    kryo.addDefaultSerializer(classOf[DatasetGraph], classOf[DatasetGraphSerializer])
    kryo.register(classOf[DatasetGraph], new DatasetGraphSerializer)

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
        case Iri(value)   =>
          kryo.writeObject(output, graph.value)
          kryo.writeObject(output, value)
        case bNode: BNode =>
          kryo.writeObject(output, graph.replace(bNode, fakeIri).value)
          kryo.writeObject(output, fakeIRI)
      }

    override def read(kryo: Kryo, input: Input, `type`: Class[_ <: Graph]): Graph = {
      val graph = kryo.readObject(input, classOf[DatasetGraph])
      kryo.readObject(input, classOf[IRI]) match {
        case `fakeIRI` =>
          val bNode = BNode.random
          Graph.unsafe(bNode, graph).replace(fakeIri, bNode)
        case other     =>
          Graph.unsafe(Iri.unsafe(other.toString), graph)
      }
    }
  }

  private[serialization] class DatasetGraphSerializer extends Serializer[DatasetGraph] {

    override def write(kryo: Kryo, output: Output, graph: DatasetGraph): Unit =
      output.writeString(RDFWriter.create.lang(Lang.NQUADS).source(graph).asString())

    override def read(kryo: Kryo, input: Input, `type`: Class[_ <: DatasetGraph]): DatasetGraph = {
      val g = DatasetFactory.create().asDatasetGraph()
      RDFParser.create().fromString(input.readString()).lang(Lang.NQUADS).parse(g)
      g
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
