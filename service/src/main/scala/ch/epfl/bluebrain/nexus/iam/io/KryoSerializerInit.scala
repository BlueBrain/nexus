package ch.epfl.bluebrain.nexus.iam.io

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
