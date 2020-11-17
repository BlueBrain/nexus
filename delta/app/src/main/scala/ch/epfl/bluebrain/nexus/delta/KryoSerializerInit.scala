package ch.epfl.bluebrain.nexus.delta

import java.nio.file.Path

import ch.epfl.bluebrain.nexus.delta.KryoSerializerInit._
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo
import org.apache.jena.iri.{IRI, IRIFactory}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.{Lang, RDFParser, RDFWriter}

class KryoSerializerInit extends DefaultKryoInitializer {

  override def postInit(kryo: ScalaKryo): Unit = {
    super.postInit(kryo)
    kryo.addDefaultSerializer(classOf[IRI], classOf[IRISerializer])
    kryo.register(classOf[IRI], new IRISerializer)

    kryo.addDefaultSerializer(classOf[Model], classOf[ModelSerializer])
    kryo.register(classOf[Model], new ModelSerializer)

    kryo.addDefaultSerializer(classOf[Path], classOf[PathSerializer])
    kryo.register(classOf[Path], new PathSerializer)

    ()
  }
}

object KryoSerializerInit {

  class ModelSerializer extends Serializer[Model] {

    override def write(kryo: Kryo, output: Output, model: Model): Unit =
      output.writeString(RDFWriter.create.lang(Lang.NTRIPLES).source(model).asString())

    override def read(kryo: Kryo, input: Input, `type`: Class[Model]): Model = {
      val model = ModelFactory.createDefaultModel()
      RDFParser.create().fromString(input.readString()).lang(Lang.NTRIPLES).parse(model)
      model
    }
  }

  class IRISerializer extends Serializer[IRI] {
    private val iriFactory = IRIFactory.iriImplementation()

    override def write(kryo: Kryo, output: Output, iri: IRI): Unit =
      output.writeString(iri.toString)

    override def read(kryo: Kryo, input: Input, `type`: Class[IRI]): IRI =
      iriFactory.create(input.readString())
  }

  class PathSerializer extends Serializer[Path] {

    override def write(kryo: Kryo, output: Output, path: Path): Unit =
      output.writeString(path.toString)

    override def read(kryo: Kryo, input: Input, `type`: Class[Path]): Path =
      Path.of(input.readString())
  }
}
