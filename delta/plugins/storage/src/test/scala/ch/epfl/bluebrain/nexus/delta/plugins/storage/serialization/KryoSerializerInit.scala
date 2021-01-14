package ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization

import akka.actor.ExtendedActorSystem
import ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization.KryoSerializerInit.{IRISerializer, PathSerializer}
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo
import org.apache.jena.iri.{IRI, IRIFactory}

import java.nio.file.Path

//TODO: partially ported from service module, we might want to avoid this duplication
class KryoSerializerInit extends DefaultKryoInitializer {

  override def postInit(kryo: ScalaKryo, system: ExtendedActorSystem): Unit = {
    super.postInit(kryo, system)

    kryo.addDefaultSerializer(classOf[IRI], classOf[IRISerializer])
    kryo.register(classOf[IRI], new IRISerializer)

    kryo.addDefaultSerializer(classOf[Path], classOf[PathSerializer])
    kryo.register(classOf[Path], new PathSerializer)
    ()
  }
}

object KryoSerializerInit {

  private val iriFactory = IRIFactory.iriImplementation()

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
