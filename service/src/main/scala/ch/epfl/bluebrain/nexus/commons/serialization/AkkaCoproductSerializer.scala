package ch.epfl.bluebrain.nexus.commons.serialization

import akka.serialization.SerializerWithStringManifest
import shapeless._
import shapeless.ops.coproduct.Unifier

/**
  * An Akka ''SerializerWithStringManifest'' implementation based on an underlying ''CoproductSerializer''.
  *
  * @param identifier the serializer identifier
  * @param C          the types available for serialization / deserialization encoded as a coproduct
  * @param U          a unifier instance for the coproduct ''C''
  * @tparam C the type of the underlying coproduct
  */
class AkkaCoproductSerializer[C <: Coproduct](override val identifier: Int)(
    implicit C: CoproductSerializer[C],
    U: Unifier[C]
) extends SerializerWithStringManifest {

  final override def manifest(o: AnyRef): String =
    C.manifest(o)
      .getOrElse(throw new IllegalArgumentException(s"Unable to compute manifest; unknown type '$o'"))

  final override def toBinary(o: AnyRef): Array[Byte] =
    C.toBinary(o)
      .getOrElse(throw new IllegalArgumentException(s"Unable to encode to binary; unknown type '$o'"))

  final override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    C.fromBinary(bytes, manifest)
      .map(c => c.unify.asInstanceOf[AnyRef])
      .getOrElse(throw new IllegalArgumentException(s"Unable to decode from binary; unknown manifest '$manifest'"))
}
