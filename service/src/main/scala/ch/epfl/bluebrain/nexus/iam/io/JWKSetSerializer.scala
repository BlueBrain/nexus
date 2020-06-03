package ch.epfl.bluebrain.nexus.iam.io

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import com.nimbusds.jose.jwk.JWKSet

class JWKSetSerializer extends Serializer[JWKSet] {

  override def write(kryo: Kryo, output: Output, `object`: JWKSet): Unit =
    output.writeString(`object`.toJSONObject.toJSONString)

  override def read(kryo: Kryo, input: Input, `type`: Class[JWKSet]): JWKSet =
    JWKSet.parse(input.readString)
}
