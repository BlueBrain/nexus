package ch.epfl.bluebrain.nexus.marshallers

import java.nio.file.Path

import ch.epfl.bluebrain.nexus.marshallers.KryoSerializerInit._
import com.esotericsoftware.kryo.{Kryo, Serializer}
import com.esotericsoftware.kryo.io.{Input, Output}
import com.nimbusds.jose.jwk.JWKSet
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo

class KryoSerializerInit extends DefaultKryoInitializer {

  override def postInit(kryo: ScalaKryo): Unit = {
    super.postInit(kryo)

    kryo.addDefaultSerializer(classOf[JWKSet], classOf[JWKSetSerializer])
    kryo.register(classOf[JWKSet], new JWKSetSerializer)

    kryo.addDefaultSerializer(classOf[Path], classOf[PathSerializer])
    kryo.register(classOf[Path], new PathSerializer)

    ()
  }
}

object KryoSerializerInit {

  private[marshallers] class JWKSetSerializer extends Serializer[JWKSet] {

    override def write(kryo: Kryo, output: Output, `object`: JWKSet): Unit =
      output.writeString(`object`.toJSONObject.toJSONString)

    override def read(kryo: Kryo, input: Input, `type`: Class[JWKSet]): JWKSet =
      JWKSet.parse(input.readString)
  }

  private[marshallers] class PathSerializer extends Serializer[Path] {

    override def write(kryo: Kryo, output: Output, path: Path): Unit =
      output.writeString(path.toString)

    override def read(kryo: Kryo, input: Input, `type`: Class[Path]): Path =
      Path.of(input.readString())
  }
}
