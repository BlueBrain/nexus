package ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization

import akka.actor.ExtendedActorSystem
import ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization.KryoSerializerInit.PathSerializer
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo

import java.nio.file.Path

//TODO: partially ported from service module, we might want to avoid this duplication
class KryoSerializerInit extends DefaultKryoInitializer {

  override def postInit(kryo: ScalaKryo, system: ExtendedActorSystem): Unit = {
    super.postInit(kryo, system)

    kryo.addDefaultSerializer(classOf[Path], classOf[PathSerializer])
    kryo.register(classOf[Path], new PathSerializer)
    ()
  }
}

object KryoSerializerInit {

  private[serialization] class PathSerializer extends Serializer[Path] {

    override def write(kryo: Kryo, output: Output, path: Path): Unit =
      output.writeString(path.toString)

    override def read(kryo: Kryo, input: Input, `type`: Class[_ <: Path]): Path =
      Path.of(input.readString())
  }
}
