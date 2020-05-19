package ch.epfl.bluebrain.nexus.marshallers

import ch.epfl.bluebrain.nexus.marshallers.KryoSerializerInit.JWKSetSerializer
import com.esotericsoftware.kryo.{Kryo, Serializer}
import com.esotericsoftware.kryo.io.{Input, Output}
import com.nimbusds.jose.jwk.JWKSet
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo

class KryoSerializerInit extends DefaultKryoInitializer {

  override def postInit(kryo: ScalaKryo): Unit = {
    super.postInit(kryo)
    kryo.register(classOf[JWKSet], new JWKSetSerializer)
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
}
